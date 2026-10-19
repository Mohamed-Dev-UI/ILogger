package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

internal fun DispatchMode.resolve(default: DispatchMode): DispatchMode =
    if (this == DispatchMode.DEFAULT) default else this

/** Coroutine-context marker that disables recursive dispatch from inside sinks. */
internal class RecursionMarker :
    AbstractCoroutineContextElement(RecursionMarker) {

    public companion object Key : CoroutineContext.Key<RecursionMarker>
}

private val RECURSION_MARKER = RecursionMarker()

internal sealed interface Queued {
    class Event internal constructor(internal val event: LogEvent) : Queued
    class Flush internal constructor(internal val completion: CompletableDeferred<Unit>) : Queued
}

/**
 * The default [ILogger]: routes events to sinks with the configured dispatch
 * mode, backpressure and error policies. This class is intentionally concrete
 * (no indirection layers); precise guarantees live on [ILogger] and members.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultILogger(
    private val config: ILoggerConfig,
) : ILogger {

    private val scope = CoroutineScope(
        SupervisorJob() + (config.workerDispatcher ?: Dispatchers.Default.limitedParallelism(1, name = "ilogger-worker"))
    )

    private val queue: Channel<Queued> = Channel(
        capacity = config.bufferSize,
        onBufferOverflow = when (config.overflow) {
            OverflowPolicy.DROP_OLDEST -> BufferOverflow.DROP_OLDEST
            OverflowPolicy.DROP_NEWEST -> BufferOverflow.DROP_LATEST
            OverflowPolicy.BLOCK, OverflowPolicy.FAIL -> BufferOverflow.SUSPEND
        },
    )

    private val lifecycleLock = Any()
    private val closedFlag = AtomicBoolean(false)
    private val workerStarted = AtomicBoolean(false)

    @Volatile
    private var workerJob: Job? = null

    private val providerDepth = ThreadLocal.withInitial { 0 }
    private val syncMaxDepth = 32

    override var enabled: Boolean = config.enabled

    override fun isEnabled(level: LogLevel): Boolean =
        enabled && level.ordinal >= config.minLevel.ordinal

    // ------------------------------------------------------------------
    // Diagnostic API
    // ------------------------------------------------------------------

    override fun trace(message: String, vararg fields: Pair<String, Any?>) =
        diagnostic(LogLevel.TRACE, message, fields)

    override fun debug(message: String, vararg fields: Pair<String, Any?>) =
        diagnostic(LogLevel.DEBUG, message, fields)

    override fun info(message: String, vararg fields: Pair<String, Any?>) =
        diagnostic(LogLevel.INFO, message, fields)

    override fun warn(message: String, vararg fields: Pair<String, Any?>) =
        diagnostic(LogLevel.WARN, message, fields)

    override fun error(message: String, vararg fields: Pair<String, Any?>) =
        diagnostic(LogLevel.ERROR, message, fields)

    private fun diagnostic(level: LogLevel, message: String, fields: Array<out Pair<String, Any?>>) {
        // Cheap, allocation-free acceptance checks first: most filtered calls
        // end here at zero cost.
        if (!isEnabled(level)) return
        syncGuard {
            val action = when (level) {
                LogLevel.TRACE -> "log.trace"
                LogLevel.DEBUG -> "log.debug"
                LogLevel.INFO -> "log.info"
                LogLevel.WARN -> "log.warn"
                LogLevel.ERROR -> "log.error"
                LogLevel.AUDIT -> "log.audit"
            }
            // Diagnostics consult ONLY the thread-held ambient actor (cheap);
            // full provider resolution is reserved for audit events.
            val context = LogContexts.currentThread()
            val metadata = LinkedHashMap<String, Any?>(fields.size + 1)
            metadata["message"] = message
            for (field in fields) metadata[field.first] = field.second
            val event = EventBuilder(
                action = action,
                level = level,
                actor = context.actor,
                context = context,
                source = SOURCE_LOGGER,
            ).build(metadata)
            event?.let(::dispatchSync)
        }
    }

    // ------------------------------------------------------------------
    // Audit / event API
    // ------------------------------------------------------------------

    override suspend fun record(
        action: String,
        level: LogLevel,
        entity: String?,
        entityId: String?,
        actor: Actor?,
        outcome: Outcome,
        failure: Throwable?,
        metadata: Map<String, Any?>,
        changes: List<FieldChange>,
        source: String?,
        category: String?,
        actionVersion: Int,
        dispatch: DispatchMode,
        sampling: Double,
    ) {
        if (!enabled) return
        val mode = dispatch.resolve(config.dispatch)
        if (mode == DispatchMode.INLINE) {
            // Recursion protection: a nested record() call made from inside an
            // INLINE sink write sees the marker and is dropped.
            if (coroutineContext[RecursionMarker] != null) {
                reportRecursive()
                return
            }
            withContext(RECURSION_MARKER) {
                buildAndDispatch(
                    action, level, entity, entityId, actor, outcome, failure,
                    metadata, changes, source, category, actionVersion, sampling, mode,
                )
            }
        } else {
            buildAndDispatch(
                action, level, entity, entityId, actor, outcome, failure,
                metadata, changes, source, category, actionVersion, sampling, mode,
            )
        }
    }

    override fun recordNow(
        action: String,
        level: LogLevel,
        entity: String?,
        entityId: String?,
        actor: Actor?,
        outcome: Outcome,
        failure: Throwable?,
        metadata: Map<String, Any?>,
        changes: List<FieldChange>,
        source: String?,
        category: String?,
        actionVersion: Int,
        dispatch: DispatchMode,
        sampling: Double,
    ) {
        if (!enabled) return
        val mode = dispatch.resolve(config.dispatch)
        check(mode != DispatchMode.INLINE) {
            "recordNow cannot use INLINE dispatch; use the suspend record() for synchronous delivery"
        }
        syncGuard {
            val context = LogContexts.currentThread()
            val event = EventBuilder(
                action = action,
                level = level,
                actor = actor ?: context.actor ?: syncGuard { config.actorProvider.currentActor() },
                context = context,
                source = source,
                category = category?.takeUnless { it.isEmpty() },
                entity = entity,
                entityId = entityId,
                outcome = outcome,
                failure = failure,
                actionVersion = actionVersion,
                sampling = sampling,
            ).build(metadata, changes)
            event?.let(::dispatchSync)
        }
    }

    /** Shared suspend-side build + dispatch for [record]. */
    private suspend fun buildAndDispatch(
        action: String,
        level: LogLevel,
        entity: String?,
        entityId: String?,
        actor: Actor?,
        outcome: Outcome,
        failure: Throwable?,
        metadata: Map<String, Any?>,
        changes: List<FieldChange>,
        source: String?,
        category: String?,
        actionVersion: Int,
        sampling: Double,
        mode: DispatchMode,
    ) {
        val context = LogContexts.current()
        val event = EventBuilder(
            action = action,
            level = level,
            // Precedence: explicit → ambient context → configured provider.
            actor = actor ?: context.actor ?: syncGuard { config.actorProvider.currentActor() },
            context = context,
            source = source,
            category = category?.takeUnless { it.isEmpty() },
            entity = entity,
            entityId = entityId,
            outcome = outcome,
            failure = failure,
            actionVersion = actionVersion,
            sampling = sampling,
        ).build(metadata, changes) ?: return
        if (mode == DispatchMode.INLINE) inlineWrite(event) else enqueueAsync(event)
    }

    private inner class EventBuilder(
        private val action: String,
        private val level: LogLevel,
        private val actor: Actor?,
        private val context: LogContext,
        private val source: String?,
        private val category: String? = null,
        private val entity: String? = null,
        private val entityId: String? = null,
        private val outcome: Outcome = Outcome.SUCCESS,
        private val failure: Throwable? = null,
        private val actionVersion: Int = 1,
        private val sampling: Double = -1.0,
    ) {
        /**
         * Builds the event honoring suppression and sampling. Returns null when
         * suppressed, meaning no event allocation was made.
         */
        fun build(metadata: Map<String, Any?>, changes: List<FieldChange> = emptyList()): LogEvent? {
            if (source != null && context.suppressSources.contains(source)) return null
            if (!sample(level, sampling)) return null
            return LogEvent(
                id = config.idGenerator.next(),
                timestampMillis = config.clock.nowMillis(),
                level = level,
                action = action,
                actionVersion = actionVersion,
                actor = actor,
                entity = entity,
                entityId = entityId,
                metadata = metadata,
                changes = changes,
                source = source,
                category = category,
                correlationId = context.correlationId,
                causationId = context.causationId,
                outcome = outcome,
                failure = failure?.let(LogFailure::from),
            )
        }
    }

    private fun sample(level: LogLevel, overrideRate: Double): Boolean {
        if (overrideRate in 0.0..1.0) {
            // Annotation-level sampling of audit events is rejected at compile
            // time; this runtime fallback deliberately keeps audit events intact.
            if (level == LogLevel.AUDIT) return true
            return Random.Default.nextDouble() < overrideRate
        }
        return config.sampler?.shouldSample(level, "log.${level.name.lowercase()}") ?: true
    }

    private fun <T> syncGuard(block: () -> T): T? {
        val depth = providerDepth.get()
        if (depth >= syncMaxDepth) {
            reportError(null, null, IllegalStateException("ilogger: recursive dispatch detected and dropped"))
            return null
        }
        providerDepth.set(depth + 1)
        try {
            return block()
        } finally {
            providerDepth.set(depth)
        }
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    /** Non-suspending dispatch used by diagnostics and [recordNow]. */
    private fun dispatchSync(event: LogEvent) {
        if (config.dispatch == DispatchMode.INLINE) {
            // INLINE diagnostics block until written: acceptable for explicitly
            // synchronous configurations (e.g. the console default logger).
            runBlocking {
                withContext(RECURSION_MARKER) { inlineWrite(event) }
            }
        } else {
            enqueueSync(event)
        }
    }

    private fun enqueueSync(event: LogEvent) {
        startWorkerIfNeeded()
        val payload = Queued.Event(event)
        when (config.overflow) {
            OverflowPolicy.DROP_OLDEST, OverflowPolicy.DROP_NEWEST -> {
                val result = queue.trySend(payload)
                if (result.isClosed) reportClosed()
                else if (!result.isSuccess) reportOverflow(event)
            }
            OverflowPolicy.BLOCK -> runCatching { queue.trySendBlocking(payload) }.onFailure { error ->
                if (error is CancellationException) throw error
                reportError(event, null, error)
            }
            OverflowPolicy.FAIL -> throw IllegalStateException(
                "ilogger dispatch buffer full (overflow=FAIL) while recording '${event.action}'"
            )
        }
    }

    private suspend fun enqueueAsync(event: LogEvent) {
        startWorkerIfNeeded()
        val payload = Queued.Event(event)
        when (config.overflow) {
            OverflowPolicy.DROP_OLDEST, OverflowPolicy.DROP_NEWEST -> {
                val result = queue.trySend(payload)
                if (result.isClosed) reportClosed()
                else if (!result.isSuccess) reportOverflow(event)
            }
            OverflowPolicy.BLOCK -> try {
                queue.send(payload)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportError(event, null, error)
            }
            OverflowPolicy.FAIL -> {
                if (!queue.trySend(payload).isSuccess) throw IllegalStateException(
                    "ilogger dispatch buffer full (overflow=FAIL) while recording '${event.action}'"
                )
            }
        }
    }

    private suspend fun inlineWrite(event: LogEvent) {
        for (binding in routesFor(event.category)) {
            writeWithPolicy(binding, event, allowPropagate = true)
        }
    }

    private suspend fun writeWithPolicy(binding: SinkBinding, event: LogEvent, allowPropagate: Boolean) {
        val mode = effectiveErrorMode(event.level)
        var attempt = 0
        while (true) {
            try {
                binding.sink.write(event)
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                when (mode) {
                    ErrorMode.PROPAGATE ->
                        if (allowPropagate) throw error
                        else {
                            reportError(event, binding.sink, error)
                            return
                        }
                    ErrorMode.DROP -> {
                        reportError(event, binding.sink, error)
                        return
                    }
                    ErrorMode.RETRY -> {
                        if (attempt >= config.retryMaxAttempts) {
                            reportError(event, binding.sink, error)
                            return
                        }
                        delay(backoffMillis(attempt))
                        attempt++
                    }
                }
            }
        }
    }

    private fun effectiveErrorMode(level: LogLevel): ErrorMode =
        config.errorModes[level] ?: ErrorMode.DROP

    private fun backoffMillis(attempt: Int): Long =
        (config.retryBaseBackoffMillis * (1L shl attempt.coerceIn(0, 14))).coerceAtMost(10_000L)

    private fun reportOverflow(event: LogEvent) {
        reportError(event, null, IllegalStateException("ilogger dispatch buffer full (overflow=${config.overflow})"))
    }

    private fun reportClosed() {
        reportError(null, null, IllegalStateException("ilogger: logger closed, event discarded"))
    }

    private fun reportRecursive() {
        reportError(null, null, IllegalStateException("ilogger: recursive dispatch detected and dropped"))
    }

    private fun reportError(event: LogEvent?, sink: LogSink?, error: Throwable) {
        try {
            config.onDispatchError?.invoke(event, sink, error)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // Pipeline lifecycle
    // ------------------------------------------------------------------

    private fun startWorkerIfNeeded() {
        if (closedFlag.get()) return
        synchronized(lifecycleLock) {
            if (workerStarted.get() || closedFlag.get()) return
            workerStarted.set(true)
            val dispatcher =
                config.workerDispatcher ?: Dispatchers.Default.limitedParallelism(1, name = "ilogger-worker")
            workerJob = scope.launch(dispatcher) {
                withContext(RECURSION_MARKER) { runPipeline() }
            }
        }
    }

    private suspend fun runPipeline() {
        try {
            if (config.dispatch == DispatchMode.BUFFERED) batchLoop() else serialLoop()
        } finally {
            flushAllBindings()
        }
    }

    private suspend fun serialLoop() {
        for (queued in queue) {
            when (queued) {
                is Queued.Event -> {
                    val event = queued.event
                    for (binding in routesFor(event.category)) {
                        writeWithPolicy(binding, event, allowPropagate = false)
                    }
                }
                is Queued.Flush -> {
                    flushAllBindings()
                    queued.completion.complete(Unit)
                }
            }
        }
    }

    private suspend fun batchLoop() {
        val batch = ArrayList<LogEvent>(config.batchSize)
        for (queued in queue) {
            when (queued) {
                is Queued.Event -> {
                    batch.add(queued.event)
                    while (batch.size < config.batchSize) {
                        val immediate = queue.tryReceive()
                        if (immediate.isSuccess) {
                            when (val item = immediate.getOrNull()) {
                                is Queued.Event -> batch.add(item.event)
                                is Queued.Flush -> completeFlushMidBatch(batch, item)
                                else -> {} // closed/suppressed bit pattern; loop below detects close
                            }
                            continue
                        }
                        val waited = withTimeoutOrNull(config.flushIntervalMillis) {
                            runCatching { queue.receive() }.getOrNull()
                        } ?: break
                        when (waited) {
                            is Queued.Event -> batch.add(waited.event)
                            is Queued.Flush -> completeFlushMidBatch(batch, waited)
                        }
                    }
                    if (batch.isNotEmpty()) {
                        writeBatch(batch)
                        batch.clear()
                    }
                }
                is Queued.Flush -> {
                    flushAllBindings()
                    queued.completion.complete(Unit)
                }
            }
        }
    }

    /** Writes whatever is gathered in [batch], flushes, and completes a flush marker. */
    private suspend fun completeFlushMidBatch(batch: MutableList<LogEvent>, flush: Queued.Flush) {
        if (batch.isNotEmpty()) {
            writeBatch(batch)
            batch.clear()
        }
        flushAllBindings()
        flush.completion.complete(Unit)
    }

    private suspend fun writeBatch(batch: List<LogEvent>) {
        val groups = LinkedHashMap<SinkBinding, ArrayList<LogEvent>>(8)
        for (event in batch) {
            for (binding in routesFor(event.category)) {
                groups.getOrPut(binding) { ArrayList(8) }.add(event)
            }
        }
        for ((binding, group) in groups) {
            val attemptable = group.any { effectiveErrorMode(it.level) != ErrorMode.DROP }
            val mode = if (attemptable) ErrorMode.RETRY else ErrorMode.DROP
            var attempt = 0
            while (true) {
                try {
                    binding.sink.writeAll(group)
                    break
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (mode == ErrorMode.RETRY && attempt < config.retryMaxAttempts) {
                        attempt++
                        delay(backoffMillis(attempt))
                        continue
                    }
                    reportError(group.firstOrNull(), binding.sink, error)
                    break
                }
            }
        }
    }

    private fun routesFor(category: String?): List<SinkBinding> {
        if (config.bindings.isEmpty()) return emptyList()
        val result = ArrayList<SinkBinding>(4)
        for (binding in config.bindings) {
            if (binding.category == null || binding.category == category) result.add(binding)
        }
        return result
    }

    private suspend fun flushAllBindings() {
        if (config.bindings.isEmpty()) return
        val seen = HashSet<LogSink>(4)
        for (binding in config.bindings) {
            if (!seen.add(binding.sink)) continue
            try {
                binding.sink.flush()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportError(null, binding.sink, error)
            }
        }
    }

    override fun flush() {
        if (closedFlag.get()) return
        runBlocking {
            withTimeoutOrNull(config.closeTimeoutMillis) {
                val worker = workerJob
                if (worker != null && !worker.isCompleted) {
                    val completion = CompletableDeferred<Unit>()
                    // trySend never blocks; correctness of ordering is best-effort.
                    val delivered = queue.trySend(Queued.Flush(completion)).isSuccess
                    if (delivered) completion.await() else flushAllBindings()
                } else {
                    flushAllBindings()
                }
            }
        }
    }

    override fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        queue.close()
        val worker = workerJob
        if (worker != null) {
            runBlocking {
                withTimeoutOrNull(config.closeTimeoutMillis) { worker.join() }
            }
        }
        scope.cancel()
        for (binding in config.bindings) {
            try {
                binding.sink.close()
            } catch (error: Throwable) {
                reportError(null, binding.sink, error)
            }
        }
    }

    private companion object {
        const val SOURCE_LOGGER = "ILogger"
    }
}
