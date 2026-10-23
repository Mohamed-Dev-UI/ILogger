package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
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

/**
 * The default [ILogger]: one bounded queue and one worker set per sink
 * binding, so a slow or failing sink cannot delay the others. Counters are
 * updated on every admission, delivery and loss path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultILogger(
    private val config: ILoggerConfig,
) : ILogger {

    override val extractors: ExtractorRegistry get() = config.extractors

    private val scope = CoroutineScope(
        SupervisorJob() + (config.workerDispatcher ?: Dispatchers.IO),
    )

    private val admissionLock = Any()
    private val capacityMonitor = java.lang.Object()
    private val capacitySignal = Channel<Unit>(Channel.CONFLATED)

    private val pipelines: List<BindingPipeline> =
        config.bindings.mapIndexed { index, binding -> BindingPipeline(index, binding) }

    private val deadLetters: DeadLetterPipeline? = config.deadLetterSink?.let { DeadLetterPipeline(it) }

    private val acceptedAdmissions = LongAdder()
    private val deliveredDeliveries = LongAdder()
    private val truncatedEvents = LongAdder()
    private val activeWrites = AtomicInteger()
    private val dropCounts = ConcurrentHashMap<DropReason, LongAdder>()

    private val lifecycleLock = Any()
    private val closedFlag = AtomicBoolean(false)

    @Volatile
    private var lastCloseReport: CloseReport? = null

    private val providerDepth = ThreadLocal.withInitial { 0 }
    private val syncMaxDepth = 32

    // Bounded, access-ordered cache: correlation id + action -> decision, so
    // START/SUCCESS/FAILURE of one operation are sampled consistently without
    // growing without limit in long-lived processes.
    private val operationDecisions = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_OPERATION_DECISIONS
    }

    override var enabled: Boolean = config.enabled

    override fun isEnabled(level: LogLevel): Boolean =
        enabled && level.ordinal >= config.minLevel.ordinal

    override fun isLoggable(level: LogLevel, action: String, category: String?): Boolean {
        if (!enabled || closedFlag.get() || !isEnabled(level)) return false
        val ambient = LogContexts.currentThread()
        return sample(
            level = level,
            overrideRate = -1.0,
            action = action,
            category = category,
            outcome = Outcome.SUCCESS,
            metadata = emptyMap(),
            operationKey = ambient.correlationId,
            actor = ambient.actor,
        )
    }

    override fun hasSinks(): Boolean =
        enabled && !closedFlag.get() && config.bindings.isNotEmpty()

    override fun isBacklogged(): Boolean {
        if (!enabled || closedFlag.get()) return false
        return synchronized(admissionLock) { pipelines.any { !it.hasCapacity() } }
    }

    override fun availableCapacity(): Int {
        if (!enabled || closedFlag.get()) return 0
        return synchronized(admissionLock) {
            if (pipelines.isEmpty()) 0 else pipelines.minOf { it.remainingCapacity() }
        }
    }

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
        if (closedFlag.get()) {
            notifyDrop(null, DropReason.CLOSED, null)
            return
        }
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
            event?.let(::dispatchDiagnostic)
        }
    }

    /**
     * Diagnostics never block on a saturated queue and never allocate coroutine
     * machinery when the matched sinks support a direct synchronous write.
     */
    private fun dispatchDiagnostic(event: LogEvent) {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return
        }
        if (config.dispatch == DispatchMode.INLINE) {
            if (routes.all { it.binding.sink is DirectLogSink }) {
                for (pipeline in routes) writeDirectDiagnostic(pipeline, event)
                return
            }
            val finished = runBlocking {
                withTimeoutOrNull(config.blockTimeoutMillis) {
                    withContext(RECURSION_MARKER) { inlineWrite(event) }
                } != null
            }
            if (!finished) {
                notifyDrop(
                    event,
                    DropReason.SINK_FAILURE,
                    IllegalStateException("ilogger: inline diagnostic timed out after ${config.blockTimeoutMillis} ms"),
                )
            }
            return
        }
        enqueueDiagnostic(event)
    }

    private fun writeDirectDiagnostic(pipeline: BindingPipeline, event: LogEvent): Boolean {
        val sink = pipeline.binding.sink as DirectLogSink
        acceptedAdmissions.increment()
        activeWrites.incrementAndGet()
        return try {
            sink.writeDirect(event)
            deliveredDeliveries.increment()
            true
        } catch (error: Throwable) {
            reportError(event, pipeline.binding.sink, error)
            notifyDrop(event, DropReason.SINK_FAILURE, error)
            false
        } finally {
            activeWrites.decrementAndGet()
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
        if (closedFlag.get()) {
            notifyDrop(null, DropReason.CLOSED, null)
            return
        }
        val mode = dispatch.resolve(config.dispatch)
        if (mode == DispatchMode.INLINE) {
            // Recursion protection: a nested record() call made from inside an
            // INLINE sink write sees the marker and is dropped.
            if (coroutineContext[RecursionMarker] != null) {
                reportRecursive()
                return
            }
            withContext(RECURSION_MARKER) {
                val event = buildSuspendEvent(
                    action, level, entity, entityId, actor, outcome, failure,
                    metadata, changes, source, category, actionVersion, sampling,
                ) ?: return@withContext
                inlineWrite(event)
            }
        } else {
            val event = buildSuspendEvent(
                action, level, entity, entityId, actor, outcome, failure,
                metadata, changes, source, category, actionVersion, sampling,
            ) ?: return
            admitSuspend(event)
        }
    }

    private suspend fun buildSuspendEvent(
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
    ): LogEvent? {
        val context = LogContexts.current()
        return EventBuilder(
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
        ).build(metadata, changes)
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
        if (closedFlag.get()) {
            notifyDrop(null, DropReason.CLOSED, null)
            return
        }
        val mode = dispatch.resolve(config.dispatch)
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
            ).build(metadata, changes) ?: return@syncGuard
            when (mode) {
                DispatchMode.INLINE -> deliverInlineSync(event)
                else -> admitSync(event)
            }
        }
    }

    override fun recordBlocking(
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
        timeoutMillis: Long,
    ): Boolean {
        if (!enabled) return false
        if (closedFlag.get()) {
            notifyDrop(null, DropReason.CLOSED, null)
            return false
        }
        val mode = dispatch.resolve(config.dispatch)
        return syncGuard {
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
            ).build(metadata, changes) ?: return@syncGuard false
            when (mode) {
                DispatchMode.INLINE -> deliverInlineBlocking(event, timeoutMillis)
                else -> admitBlocking(event, timeoutMillis)
            }
        } ?: false
    }

    override fun tryRecord(
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
    ): Boolean {
        if (!enabled || closedFlag.get()) return false
        val mode = dispatch.resolve(config.dispatch)
        return syncGuard {
            val event = try {
                val context = LogContexts.currentThread()
                EventBuilder(
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
            } catch (error: Exception) {
                // Strict THROW and provider failures must not escape an
                // opportunistic call; drops/errors were already reported.
                reportError(null, null, error)
                null
            } ?: return@syncGuard false
            when (mode) {
                DispatchMode.INLINE -> deliverInlineOpportunistic(event)
                else -> enqueueOpportunistic(event)
            }
        } ?: false
    }

    /** INLINE tryRecord: only sinks that cannot block are eligible. */
    private fun deliverInlineOpportunistic(event: LogEvent): Boolean {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return false
        }
        val nonDirect = routes.firstOrNull { it.binding.sink !is DirectLogSink }
        if (nonDirect != null) {
            notifyDrop(
                event,
                DropReason.SINK_FAILURE,
                IllegalStateException(
                    "ilogger: tryRecord cannot write inline to " +
                        "${nonDirect.binding.sink.javaClass.name} without blocking",
                ),
            )
            return false
        }
        var allWritten = true
        for (pipeline in routes) {
            if (!writeDirectDiagnostic(pipeline, event)) allWritten = false
        }
        return allWritten
    }

    /** Non-blocking admission: full queues drop instead of applying BLOCK/FAIL. */
    private fun enqueueOpportunistic(event: LogEvent): Boolean {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return false
        }
        val dropped = ArrayList<DroppedEvent>(1)
        var acceptedAny = false
        var acceptedAll = true
        synchronized(admissionLock) {
            if (closedFlag.get()) {
                dropped += DroppedEvent(event, DropReason.CLOSED, null)
                acceptedAll = false
            } else {
                for (pipeline in routes) {
                    when (
                        val result = pipeline.offer(
                            event,
                            dropOldestWhenFull = config.overflow == OverflowPolicy.DROP_OLDEST,
                        )
                    ) {
                        OfferResult.Accepted -> {
                            acceptedAdmissions.increment()
                            acceptedAny = true
                        }
                        OfferResult.DroppedNewest -> {
                            dropped += DroppedEvent(event, DropReason.OVERFLOW_NEWEST, null)
                            acceptedAll = false
                        }
                        is OfferResult.DroppedOldest -> {
                            acceptedAdmissions.increment()
                            acceptedAny = true
                            dropped += DroppedEvent(result.evicted, DropReason.OVERFLOW_OLDEST, null)
                        }
                        OfferResult.Closed -> {
                            dropped += DroppedEvent(event, DropReason.CLOSED, null)
                            acceptedAll = false
                        }
                    }
                }
            }
        }
        dropped.forEach(::notifyDrop)
        if (acceptedAny) startWorkers(routes)
        return acceptedAll && acceptedAny
    }

    private fun deliverInlineSync(event: LogEvent) {
        val finished = runBlocking {
            withTimeoutOrNull(config.blockTimeoutMillis) {
                withContext(RECURSION_MARKER) { inlineWrite(event) }
            } != null
        }
        if (!finished) {
            notifyDrop(
                event,
                DropReason.SINK_FAILURE,
                IllegalStateException("ilogger: inline delivery timed out after ${config.blockTimeoutMillis} ms"),
            )
        }
    }

    private fun deliverInlineBlocking(event: LogEvent, timeoutMillis: Long): Boolean {
        val finished = runBlocking {
            withTimeoutOrNull(timeoutMillis.coerceAtLeast(0)) {
                withContext(RECURSION_MARKER) { inlineWrite(event) }
            } != null
        }
        if (!finished) {
            notifyDrop(
                event,
                DropReason.SINK_FAILURE,
                IllegalStateException("ilogger: inline delivery timed out after $timeoutMillis ms"),
            )
        }
        return finished
    }

    private fun admitSync(event: LogEvent) {
        when (config.overflow) {
            OverflowPolicy.DROP_OLDEST, OverflowPolicy.DROP_NEWEST -> enqueueDrop(event)
            OverflowPolicy.BLOCK -> enqueueBlockingSync(event)
            OverflowPolicy.FAIL -> enqueueFail(event)
        }
    }

    private suspend fun admitSuspend(event: LogEvent) {
        when (config.overflow) {
            OverflowPolicy.DROP_OLDEST, OverflowPolicy.DROP_NEWEST -> enqueueDrop(event)
            OverflowPolicy.BLOCK -> enqueueBlockingSuspend(event)
            OverflowPolicy.FAIL -> enqueueFail(event)
        }
    }

    private fun admitBlocking(event: LogEvent, timeoutMillis: Long): Boolean =
        when (config.overflow) {
            OverflowPolicy.DROP_OLDEST, OverflowPolicy.DROP_NEWEST -> enqueueDrop(event)
            OverflowPolicy.FAIL -> enqueueFail(event)
            OverflowPolicy.BLOCK -> {
                val finished = runBlocking {
                    withTimeoutOrNull(timeoutMillis.coerceAtLeast(0)) {
                        enqueueBlockingSuspend(event)
                    }
                }
                if (finished == null) {
                    notifyDrop(
                        event,
                        DropReason.OVERFLOW_NEWEST,
                        IllegalStateException("ilogger: admission timed out after $timeoutMillis ms"),
                    )
                    false
                } else {
                    finished
                }
            }
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
            // Enrichers run on the producer thread while trace/span state is
            // still current, before every guard that can rewrite or reject.
            val admittedMetadata = if (config.enrichers.isEmpty()) metadata else enrich(metadata)
            // Registered ValueAdapters render first: sinks and strict validation
            // only ever see log-safe values. Empty registry = pass-through.
            val adapters = config.valueAdapters
            val renderedMetadata = if (adapters.isEmpty) admittedMetadata else renderValues(admittedMetadata)
            val renderedChanges = if (adapters.isEmpty) {
                changes
            } else {
                changes.map { FieldChange(it.field, adapters.render(it.before), adapters.render(it.after)) }
            }
            if (source != null && context.suppressSources.contains(source)) {
                notifyDrop(null, DropReason.SUPPRESSED, null)
                return null
            }
            var eventActor = actor
            var eventEntity = entity
            var eventEntityId = entityId
            var eventSource = source
            var eventCategory = category
            var eventMetadata = renderedMetadata
            var eventChanges = renderedChanges
            var eventFailure: LogFailure? = failure?.let(LogFailure::from)
            // Redaction runs before limits so masks (not truncated secrets)
            // count toward the byte budget.
            config.redaction?.let { policy ->
                val outcome = RedactionEnforcement.apply(
                    policy = policy,
                    category = category,
                    actor = eventActor,
                    metadata = eventMetadata,
                    changes = eventChanges,
                )
                if (!outcome.accepted) {
                    val error = IllegalArgumentException("ilogger redaction: ${outcome.violation}")
                    notifyDrop(null, DropReason.STRICT_REJECT, error)
                    return null
                }
                eventActor = outcome.actor ?: eventActor
                eventMetadata = outcome.metadata
                eventChanges = outcome.changes
            }
            val limits = config.limits
            if (limits != null) {
                // Failure capture is bounded before the sanitizer sees it, so a
                // deep stack never materializes more than the configured frames.
                eventFailure = failure?.let { LogFailure.from(it, limits.maxFailureFrames) }
                val outcome = LimitsEnforcement.apply(
                    limits = limits,
                    action = action,
                    actor = eventActor,
                    entity = entity,
                    entityId = entityId,
                    source = source,
                    category = category,
                    metadata = eventMetadata,
                    changes = eventChanges,
                    failure = eventFailure,
                )
                if (!outcome.accepted) {
                    val error = IllegalArgumentException("ilogger limits: ${outcome.violation}")
                    notifyDrop(null, DropReason.STRICT_REJECT, error)
                    return null
                }
                eventActor = outcome.actor
                eventEntity = outcome.entity
                eventEntityId = outcome.entityId
                eventSource = outcome.source
                eventCategory = outcome.category
                eventMetadata = outcome.metadata
                eventChanges = outcome.changes
                eventFailure = outcome.failure
                if (outcome.truncated) truncatedEvents.increment()
            }
            if (!sample(
                    level = level,
                    overrideRate = sampling,
                    action = action,
                    category = eventCategory,
                    outcome = outcome,
                    metadata = eventMetadata,
                    operationKey = context.correlationId,
                    actor = eventActor,
                )
            ) {
                notifyDrop(null, DropReason.SAMPLED, null)
                return null
            }
            if (config.strictMode != StrictMode.OFF) {
                val violation = StrictValidation.violation(
                    action = action,
                    actor = eventActor,
                    entity = eventEntity,
                    entityId = eventEntityId,
                    source = eventSource,
                    category = eventCategory,
                    metadata = eventMetadata,
                    changes = eventChanges,
                    maxStringLength = config.strictMaxStringLength,
                    maxEntries = config.strictMaxEntries,
                    maxEventBytes = config.strictMaxEventBytes,
                )
                if (violation != null) {
                    val error = IllegalArgumentException("ilogger strict: $violation")
                    notifyDrop(null, DropReason.STRICT_REJECT, error)
                    if (config.strictMode == StrictMode.THROW) throw error
                    return null
                }
            }
            return LogEvent(
                id = config.idGenerator.next(),
                timestampMillis = config.clock.nowMillis(),
                level = level,
                action = action,
                actionVersion = actionVersion,
                actor = eventActor,
                entity = eventEntity,
                entityId = eventEntityId,
                metadata = eventMetadata,
                changes = eventChanges,
                source = eventSource,
                category = eventCategory,
                correlationId = context.correlationId,
                causationId = context.causationId,
                outcome = outcome,
                failure = eventFailure,
            )
        }

        /**
         * Explicit metadata wins; earlier enrichers win over later ones. A
         * broken enricher is reported and skipped so it can never cost an
         * event that would otherwise have been recorded.
         */
        private fun enrich(metadata: Map<String, Any?>): Map<String, Any?> {
            var merged: LinkedHashMap<String, Any?>? = null
            for (enricher in config.enrichers) {
                val extra = try {
                    enricher.enrich(context)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    reportError(null, null, error)
                    continue
                }
                if (extra.isEmpty()) continue
                val target = merged
                    ?: LinkedHashMap<String, Any?>(metadata.size + extra.size).also {
                        it.putAll(metadata)
                        merged = it
                    }
                for ((key, value) in extra) {
                    if (key !in target) target[key] = value
                }
            }
            return merged ?: metadata
        }

        private fun renderValues(metadata: Map<String, Any?>): Map<String, Any?> {
            val rendered = LinkedHashMap<String, Any?>(metadata.size)
            for ((key, value) in metadata) rendered[key] = config.valueAdapters.render(value)
            return rendered
        }
    }

    private fun sample(
        level: LogLevel,
        overrideRate: Double,
        action: String,
        category: String?,
        outcome: Outcome,
        metadata: Map<String, Any?>,
        operationKey: String?,
        actor: Actor? = null,
    ): Boolean {
        if (overrideRate in 0.0..1.0) {
            // Annotation-level sampling of audit events is rejected at compile
            // time; this runtime fallback deliberately keeps audit events intact.
            if (level == LogLevel.AUDIT) return true
            return Random.Default.nextDouble() < overrideRate
        }
        if (level == LogLevel.AUDIT && !config.auditSampling) return true
        val sampler = config.contextualSampler
            ?: config.sampler?.asContextualSampler()
            ?: return true
        val samplingContext = SamplingContext(
            level = level,
            action = action,
            category = category,
            outcome = outcome,
            metadata = metadata,
            operationKey = operationKey,
            actor = actor,
        )
        if (operationKey == null) return sampler.shouldSample(samplingContext)
        val key = "$operationKey|$action"
        return synchronized(operationDecisions) {
            operationDecisions[key]
                ?: sampler.shouldSample(samplingContext).also { operationDecisions[key] = it }
        }
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
    // Admission
    // ------------------------------------------------------------------

    /** Non-blocking admission for `DROP_*` policies; one report per lost delivery. */
    private fun enqueueDrop(event: LogEvent): Boolean {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return false
        }
        val dropped = ArrayList<DroppedEvent>(1)
        var accepted = false
        synchronized(admissionLock) {
            if (closedFlag.get()) {
                dropped += DroppedEvent(event, DropReason.CLOSED, null)
            } else {
                for (pipeline in routes) {
                    when (val result = pipeline.offer(event, dropOldestWhenFull = config.overflow == OverflowPolicy.DROP_OLDEST)) {
                        OfferResult.Accepted -> {
                            acceptedAdmissions.increment()
                            accepted = true
                        }
                        OfferResult.DroppedNewest ->
                            dropped += DroppedEvent(event, DropReason.OVERFLOW_NEWEST, null)
                        is OfferResult.DroppedOldest -> {
                            acceptedAdmissions.increment()
                            accepted = true
                            dropped += DroppedEvent(result.evicted, DropReason.OVERFLOW_OLDEST, null)
                        }
                        OfferResult.Closed ->
                            dropped += DroppedEvent(event, DropReason.CLOSED, null)
                    }
                }
            }
        }
        dropped.forEach(::notifyDrop)
        if (accepted) startWorkers(routes)
        return accepted
    }

    /** Diagnostics use the same path but always prefer recency, never blocking. */
    private fun enqueueDiagnostic(event: LogEvent) {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return
        }
        val dropped = ArrayList<DroppedEvent>(1)
        var accepted = false
        synchronized(admissionLock) {
            if (closedFlag.get()) {
                dropped += DroppedEvent(event, DropReason.CLOSED, null)
            } else {
                for (pipeline in routes) {
                    when (val result = pipeline.offer(event, dropOldestWhenFull = config.overflow == OverflowPolicy.DROP_OLDEST)) {
                        OfferResult.Accepted -> {
                            acceptedAdmissions.increment()
                            accepted = true
                        }
                        OfferResult.DroppedNewest ->
                            dropped += DroppedEvent(event, DropReason.OVERFLOW_NEWEST, null)
                        is OfferResult.DroppedOldest -> {
                            acceptedAdmissions.increment()
                            accepted = true
                            dropped += DroppedEvent(result.evicted, DropReason.OVERFLOW_OLDEST, null)
                        }
                        OfferResult.Closed ->
                            dropped += DroppedEvent(event, DropReason.CLOSED, null)
                    }
                }
            }
        }
        dropped.forEach(::notifyDrop)
        if (accepted) startWorkers(routes)
    }

    /** `FAIL` admission: all-or-nothing, throws when any matched queue is full. */
    private fun enqueueFail(event: LogEvent): Boolean {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return false
        }
        var closed = false
        var fullError: IllegalStateException? = null
        synchronized(admissionLock) {
            if (closedFlag.get()) {
                closed = true
            } else if (routes.any { !it.hasCapacity() }) {
                fullError = IllegalStateException(
                    "ilogger dispatch buffer full (overflow=FAIL) while recording '${event.action}'",
                )
            } else {
                for (pipeline in routes) {
                    pipeline.add(event)
                    acceptedAdmissions.increment()
                }
            }
        }
        if (closed) {
            notifyDrop(event, DropReason.CLOSED, null)
            return false
        }
        val error = fullError
        if (error != null) {
            notifyDrop(event, DropReason.OVERFLOW_NEWEST, error)
            throw error
        }
        startWorkers(routes)
        return true
    }

    /** Suspendable `BLOCK` admission, bounded by `blockTimeoutMillis`. */
    private suspend fun enqueueBlockingSuspend(event: LogEvent): Boolean {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return false
        }
        val deadline = deadlineAfter(config.blockTimeoutMillis)
        while (true) {
            var accepted = false
            var closed = false
            synchronized(admissionLock) {
                when {
                    closedFlag.get() -> closed = true
                    routes.all { it.hasCapacity() } -> {
                        for (pipeline in routes) {
                            pipeline.add(event)
                            acceptedAdmissions.increment()
                        }
                        accepted = true
                    }
                }
            }
            if (accepted) {
                startWorkers(routes)
                return true
            }
            if (closed) {
                notifyDrop(event, DropReason.CLOSED, null)
                return false
            }
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) {
                notifyDrop(
                    event,
                    DropReason.OVERFLOW_NEWEST,
                    IllegalStateException("ilogger: timed out waiting for a free buffer slot"),
                )
                return false
            }
            withTimeoutOrNull(remaining) { capacitySignal.receiveCatching() }
        }
    }

    /** Blocking `BLOCK` admission for non-suspend producers, bounded the same way. */
    private fun enqueueBlockingSync(event: LogEvent): Boolean {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return false
        }
        val deadline = deadlineAfter(config.blockTimeoutMillis)
        while (true) {
            var accepted = false
            var closed = false
            synchronized(admissionLock) {
                when {
                    closedFlag.get() -> closed = true
                    routes.all { it.hasCapacity() } -> {
                        for (pipeline in routes) {
                            pipeline.add(event)
                            acceptedAdmissions.increment()
                        }
                        accepted = true
                    }
                }
            }
            if (accepted) {
                startWorkers(routes)
                return true
            }
            if (closed) {
                notifyDrop(event, DropReason.CLOSED, null)
                return false
            }
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) {
                notifyDrop(
                    event,
                    DropReason.OVERFLOW_NEWEST,
                    IllegalStateException("ilogger: timed out waiting for a free buffer slot"),
                )
                return false
            }
            synchronized(capacityMonitor) {
                try {
                    capacityMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    notifyDrop(
                        event,
                        DropReason.OVERFLOW_NEWEST,
                        IllegalStateException("ilogger: interrupted while waiting for a free buffer slot"),
                    )
                    return false
                }
            }
        }
    }

    private fun startWorkers(routes: List<BindingPipeline>) {
        for (pipeline in routes) pipeline.startIfNeeded()
    }

    // ------------------------------------------------------------------
    // Loss reporting
    // ------------------------------------------------------------------

    private fun notifyDrop(event: LogEvent?, reason: DropReason, error: Throwable?) {
        notifyDrop(DroppedEvent(event, reason, error))
    }

    private fun notifyDrop(dropped: DroppedEvent) {
        dropCounts.computeIfAbsent(dropped.reason) { LongAdder() }.increment()
        try {
            config.onDrop?.invoke(dropped)
        } catch (_: Throwable) {
            // A broken drop observer must never affect delivery.
        }
        deadLetters?.offer(dropped)
    }

    private fun reportError(event: LogEvent?, sink: LogSink?, error: Throwable) {
        try {
            config.onDispatchError?.invoke(event, sink, error)
        } catch (_: Throwable) {
        }
    }

    private fun reportRecursive() {
        val error = IllegalStateException("ilogger: recursive dispatch detected and dropped")
        reportError(null, null, error)
        notifyDrop(null, DropReason.SINK_FAILURE, error)
    }

    // ------------------------------------------------------------------
    // Inline delivery
    // ------------------------------------------------------------------

    private suspend fun inlineWrite(event: LogEvent) {
        val routes = routesFor(event.category)
        if (routes.isEmpty()) {
            notifyDrop(event, DropReason.MISSING_BINDING, null)
            return
        }
        for (pipeline in routes) writeInline(pipeline, event)
    }

    private suspend fun writeInline(pipeline: BindingPipeline, event: LogEvent) {
        acceptedAdmissions.increment()
        val mode = effectiveErrorMode(event.level)
        var attempt = 0
        while (true) {
            try {
                pipeline.binding.sink.write(event)
                deliveredDeliveries.increment()
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                when (mode) {
                    ErrorMode.PROPAGATE -> {
                        reportError(event, pipeline.binding.sink, error)
                        notifyDrop(event, DropReason.SINK_FAILURE, error)
                        throw error
                    }
                    ErrorMode.DROP -> {
                        reportError(event, pipeline.binding.sink, error)
                        notifyDrop(event, DropReason.SINK_FAILURE, error)
                        return
                    }
                    ErrorMode.RETRY -> {
                        if (attempt >= config.retryMaxAttempts) {
                            reportError(event, pipeline.binding.sink, error)
                            notifyDrop(event, DropReason.RETRY_EXHAUSTED, error)
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

    // ------------------------------------------------------------------
    // Per-binding pipeline
    // ------------------------------------------------------------------

    private sealed interface OfferResult {
        data object Accepted : OfferResult
        data object DroppedNewest : OfferResult
        data class DroppedOldest(val evicted: LogEvent) : OfferResult
        data object Closed : OfferResult
    }

    private inner class BindingPipeline(
        val index: Int,
        val binding: SinkBinding,
    ) {
        private val lock = Any()
        private val items = ArrayDeque<LogEvent>()
        private var closed = false

        private val writesInFlight = AtomicInteger()
        val deliveredCount = LongAdder()

        /** Total admitted events (monotonic), guarded by [lock]. */
        private var submitted: Long = 0

        /** Total processed events (delivered or dropped), guarded by [completedMonitor]. */
        private var completed: Long = 0

        private val completedMonitor = java.lang.Object()
        private val workSignal = Channel<Unit>(Channel.CONFLATED)

        private val startLock = Any()
        private val jobs = ArrayList<Job>()

        @Volatile
        private var started = false

        fun hasCapacity(): Boolean = synchronized(lock) {
            !closed && items.size < config.bufferSize
        }

        fun remainingCapacity(): Int = synchronized(lock) {
            if (closed) 0 else (config.bufferSize - items.size).coerceAtLeast(0)
        }

        fun inFlight(): Int = writesInFlight.get()

        fun beginWrite(count: Int) {
            writesInFlight.addAndGet(count)
        }

        fun endWrite(count: Int) {
            writesInFlight.addAndGet(-count)
        }

        fun offer(event: LogEvent, dropOldestWhenFull: Boolean): OfferResult {
            val result = synchronized(lock) {
                if (closed) return@synchronized OfferResult.Closed
                if (items.size < config.bufferSize) {
                    items.addLast(event)
                    submitted++
                    workSignal.trySend(Unit)
                    return@synchronized OfferResult.Accepted
                }
                if (!dropOldestWhenFull) return@synchronized OfferResult.DroppedNewest
                val evicted = items.removeFirst()
                items.addLast(event)
                submitted++
                workSignal.trySend(Unit)
                OfferResult.DroppedOldest(evicted)
            }
            // An evicted event leaves the queue unprocessed: count it as
            // resolved so flush watermarks (submitted == completed) can settle.
            if (result is OfferResult.DroppedOldest) markCompleted(1)
            return result
        }

        /** Adds without capacity checks; callers hold [admissionLock] and verified capacity. */
        fun add(event: LogEvent) {
            synchronized(lock) {
                if (closed) return
                items.addLast(event)
                submitted++
                workSignal.trySend(Unit)
            }
        }

        fun submittedCount(): Long = synchronized(lock) { submitted }

        fun queueSize(): Int = synchronized(lock) { items.size }

        fun poll(max: Int): List<LogEvent> {
            val drained = synchronized(lock) {
                if (items.isEmpty()) return emptyList()
                val count = minOf(max, items.size)
                val out = ArrayList<LogEvent>(count)
                repeat(count) { out += items.removeFirst() }
                out
            }
            notifyCapacity()
            return drained
        }

        fun drainAll(): List<LogEvent> {
            val drained = synchronized(lock) {
                if (items.isEmpty()) return emptyList()
                val out = ArrayList<LogEvent>(items)
                items.clear()
                out
            }
            notifyCapacity()
            return drained
        }

        fun isClosedAndEmpty(): Boolean = synchronized(lock) { closed && items.isEmpty() }

        fun closeQueue() {
            val notify: Boolean
            synchronized(lock) {
                notify = !closed
                closed = true
                workSignal.close()
            }
            if (notify) notifyCompletedWaiters()
        }

        fun markCompleted(count: Int) {
            if (count == 0) return
            synchronized(completedMonitor) {
                completed += count
                completedMonitor.notifyAll()
            }
        }

        fun awaitCompleted(target: Long, deadlineNanos: Long): Boolean {
            synchronized(completedMonitor) {
                while (completed < target) {
                    val remaining = remainingMillis(deadlineNanos)
                    if (remaining <= 0) return completed >= target
                    try {
                        completedMonitor.wait(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return completed >= target
                    }
                }
                return true
            }
        }

        /** Stronger barrier used when `sinkConcurrency > 1` (completion order is undefined). */
        fun awaitFullyIdle(deadlineNanos: Long): Boolean {
            while (true) {
                val target = submittedCount()
                var reached = false
                synchronized(completedMonitor) {
                    if (completed >= target) {
                        reached = true
                    } else {
                        val remaining = remainingMillis(deadlineNanos)
                        if (remaining <= 0) return completed >= target
                        try {
                            completedMonitor.wait(remaining)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return completed >= target
                        }
                    }
                }
                if (reached) return true
            }
        }

        suspend fun awaitWork(timeoutMillis: Long) {
            if (timeoutMillis <= 0) {
                workSignal.receiveCatching()
            } else {
                withTimeoutOrNull(timeoutMillis) { workSignal.receiveCatching() }
            }
        }

        fun startIfNeeded() {
            if (started) return
            synchronized(startLock) {
                if (started) return
                started = true
                val dispatcher = config.workerDispatcher
                    ?: Dispatchers.IO.limitedParallelism(
                        config.sinkConcurrency.coerceAtLeast(1),
                        name = "ilogger-sink-$index",
                    )
                repeat(config.sinkConcurrency.coerceAtLeast(1)) {
                    jobs += scope.launch(dispatcher) {
                        withContext(RECURSION_MARKER) { runPipeline(this@BindingPipeline) }
                    }
                }
            }
        }

        fun jobsSnapshot(): List<Job> = synchronized(startLock) { jobs.toList() }

        private fun notifyCapacity() {
            capacitySignal.trySend(Unit)
            synchronized(capacityMonitor) { capacityMonitor.notifyAll() }
        }

        private fun notifyCompletedWaiters() {
            synchronized(completedMonitor) { completedMonitor.notifyAll() }
        }
    }

    private suspend fun runPipeline(pipeline: BindingPipeline) {
        try {
            if (config.dispatch == DispatchMode.BUFFERED) bufferedLoop(pipeline) else serialLoop(pipeline)
        } catch (cancellation: CancellationException) {
            drainQueue(pipeline, DropReason.CLOSED, cancellation)
            throw cancellation
        } catch (error: Throwable) {
            reportError(null, pipeline.binding.sink, error)
            drainQueue(pipeline, DropReason.SINK_FAILURE, error)
        } finally {
            // Cancelled workers skip the flush: close() flushes every sink again
            // after joining, and a hung flush here would delay shutdown further.
            if (currentCoroutineContext().isActive) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(config.closeTimeoutMillis.coerceAtLeast(1)) { flushPipeline(pipeline) }
                }
            }
        }
    }

    private suspend fun serialLoop(pipeline: BindingPipeline) {
        while (true) {
            val batch = pipeline.poll(1)
            if (batch.isEmpty()) {
                if (pipeline.isClosedAndEmpty()) return
                pipeline.awaitWork(0)
                continue
            }
            processBatch(pipeline, batch, batchWrite = false)
        }
    }

    private suspend fun bufferedLoop(pipeline: BindingPipeline) {
        while (true) {
            val first = pipeline.poll(config.batchSize)
            if (first.isEmpty()) {
                if (pipeline.isClosedAndEmpty()) return
                pipeline.awaitWork(config.flushIntervalMillis)
                continue
            }
            val batch = ArrayList<LogEvent>(config.batchSize)
            batch.addAll(first)
            if (config.flushIntervalMillis > 0) {
                val deadline = deadlineAfter(config.flushIntervalMillis)
                while (batch.size < config.batchSize) {
                    val more = pipeline.poll(config.batchSize - batch.size)
                    if (more.isNotEmpty()) {
                        batch.addAll(more)
                        continue
                    }
                    if (pipeline.isClosedAndEmpty()) break
                    val remaining = remainingMillis(deadline)
                    if (remaining <= 0) break
                    pipeline.awaitWork(remaining)
                }
            } else {
                while (batch.size < config.batchSize) {
                    val more = pipeline.poll(config.batchSize - batch.size)
                    if (more.isEmpty()) break
                    batch.addAll(more)
                }
            }
            processBatch(pipeline, batch, batchWrite = true)
        }
    }

    private suspend fun processBatch(pipeline: BindingPipeline, batch: List<LogEvent>, batchWrite: Boolean) {
        activeWrites.addAndGet(batch.size)
        pipeline.beginWrite(batch.size)
        try {
            if (batchWrite) {
                writeBatch(pipeline, batch)
            } else {
                for (event in batch) writeQueued(pipeline, event)
            }
        } catch (cancellation: CancellationException) {
            // Forced shutdown: the batch was dequeued but not fully processed.
            for (event in batch) notifyDrop(event, DropReason.CLOSED, cancellation)
            throw cancellation
        } finally {
            activeWrites.addAndGet(-batch.size)
            pipeline.endWrite(batch.size)
            pipeline.markCompleted(batch.size)
        }
    }

    private suspend fun writeQueued(pipeline: BindingPipeline, event: LogEvent) {
        var attempt = 0
        while (true) {
            try {
                pipeline.binding.sink.write(event)
                deliveredDeliveries.increment()
                pipeline.deliveredCount.increment()
                return
            } catch (error: Throwable) {
                if (isEngineCancellation(error)) throw error
                when (effectiveErrorMode(event.level)) {
                    ErrorMode.DROP -> {
                        reportError(event, pipeline.binding.sink, error)
                        notifyDrop(event, DropReason.SINK_FAILURE, error)
                        return
                    }
                    ErrorMode.RETRY, ErrorMode.PROPAGATE -> {
                        // PROPAGATE has no producer in the async pipeline; it is
                        // downgraded to retry-then-drop, as documented on ErrorMode.
                        if (attempt >= config.retryMaxAttempts) {
                            reportError(event, pipeline.binding.sink, error)
                            notifyDrop(event, DropReason.RETRY_EXHAUSTED, error)
                            return
                        }
                        delay(backoffMillis(attempt))
                        attempt++
                    }
                }
            }
        }
    }

    private suspend fun writeBatch(pipeline: BindingPipeline, batch: List<LogEvent>) {
        val retrying = batch.any {
            val mode = effectiveErrorMode(it.level)
            mode == ErrorMode.RETRY || mode == ErrorMode.PROPAGATE
        }
        var attempt = 0
        while (true) {
            try {
                pipeline.binding.sink.writeAll(batch)
                deliveredDeliveries.add(batch.size.toLong())
                pipeline.deliveredCount.add(batch.size.toLong())
                return
            } catch (error: Throwable) {
                if (isEngineCancellation(error)) throw error
                if (retrying && attempt < config.retryMaxAttempts) {
                    delay(backoffMillis(attempt))
                    attempt++
                    continue
                }
                reportError(batch.firstOrNull(), pipeline.binding.sink, error)
                val reason = if (retrying) DropReason.RETRY_EXHAUSTED else DropReason.SINK_FAILURE
                for (event in batch) notifyDrop(event, reason, error)
                return
            }
        }
    }

    private suspend fun flushPipeline(pipeline: BindingPipeline) {
        try {
            pipeline.binding.sink.flush()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            reportError(null, pipeline.binding.sink, error)
        }
    }

    private fun drainQueue(pipeline: BindingPipeline, reason: DropReason, error: Throwable?) {
        val drained = pipeline.drainAll()
        if (drained.isNotEmpty()) {
            for (event in drained) notifyDrop(event, reason, error)
            pipeline.markCompleted(drained.size)
        }
    }

    private suspend fun isEngineCancellation(error: Throwable): Boolean =
        error is CancellationException && !currentCoroutineContext().isActive

    // ------------------------------------------------------------------
    // Dead-letter pipeline
    // ------------------------------------------------------------------

    private inner class DeadLetterPipeline(private val sink: DeadLetterSink) {

        private val lock = Any()
        private val items = ArrayDeque<DroppedEvent>()
        private var closed = false
        private var submitted: Long = 0
        private var completed: Long = 0
        private val completedMonitor = java.lang.Object()
        private val workSignal = Channel<Unit>(Channel.CONFLATED)
        private val startLock = Any()
        private val capacity = (config.bufferSize / 4).coerceAtLeast(16)
        private val workerScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(1, name = "ilogger-dead-letter"),
        )

        @Volatile
        private var job: Job? = null

        fun offer(dropped: DroppedEvent) {
            var full = false
            synchronized(lock) {
                if (closed) return
                if (items.size >= capacity) {
                    full = true
                } else {
                    items.addLast(dropped)
                    submitted++
                    workSignal.trySend(Unit)
                }
            }
            if (full) {
                reportError(
                    dropped.event,
                    null,
                    IllegalStateException("ilogger: dead-letter queue is full; dropped event discarded"),
                )
                return
            }
            startIfNeeded()
        }

        fun startIfNeeded() {
            if (job != null) return
            synchronized(startLock) {
                if (job != null) return
                job = workerScope.launch {
                    runLoop()
                }
            }
        }

        private suspend fun runLoop() {
            var currentBatch: List<DroppedEvent> = emptyList()
            var processedInBatch = 0
            try {
                while (true) {
                    val batch = poll(64)
                    currentBatch = batch
                    processedInBatch = 0
                    if (batch.isEmpty()) {
                        if (isClosedAndEmpty()) return
                        workSignal.receiveCatching()
                        continue
                    }
                    try {
                        for (dropped in batch) {
                            try {
                                sink.write(dropped)
                            } catch (cancellation: CancellationException) {
                                if (!currentCoroutineContext().isActive) throw cancellation
                                reportError(dropped.event, null, cancellation)
                            } catch (error: Throwable) {
                                reportError(dropped.event, null, error)
                            }
                            processedInBatch++
                        }
                    } finally {
                        markCompleted(processedInBatch)
                    }
                    currentBatch = emptyList()
                }
            } catch (cancellation: CancellationException) {
                loseBatchRemainder(currentBatch, processedInBatch)
                drainRemaining()
                throw cancellation
            } catch (error: Throwable) {
                reportError(null, null, error)
                loseBatchRemainder(currentBatch, processedInBatch)
                drainRemaining()
            }
        }

        /** Reports the part of a polled batch that was never handed to the sink. */
        private fun loseBatchRemainder(batch: List<DroppedEvent>, processed: Int) {
            if (processed >= batch.size) return
            for (index in processed until batch.size) {
                reportError(
                    batch[index].event,
                    null,
                    IllegalStateException("ilogger: dead-letter event lost during shutdown"),
                )
            }
            markCompleted(batch.size - processed)
        }

        private fun poll(max: Int): List<DroppedEvent> = synchronized(lock) {
            if (items.isEmpty()) return emptyList()
            val count = minOf(max, items.size)
            val out = ArrayList<DroppedEvent>(count)
            repeat(count) { out += items.removeFirst() }
            out
        }

        private fun isClosedAndEmpty(): Boolean = synchronized(lock) { closed && items.isEmpty() }

        fun closeQueue() {
            synchronized(lock) {
                closed = true
                workSignal.close()
            }
        }

        fun submittedCount(): Long = synchronized(lock) { submitted }

        fun pending(): Int = synchronized(lock) { items.size }

        private fun markCompleted(count: Int) {
            synchronized(completedMonitor) {
                completed += count
                completedMonitor.notifyAll()
            }
        }

        fun awaitCompleted(target: Long, deadlineNanos: Long): Boolean {
            synchronized(completedMonitor) {
                while (completed < target) {
                    val remaining = remainingMillis(deadlineNanos)
                    if (remaining <= 0) return completed >= target
                    try {
                        completedMonitor.wait(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return completed >= target
                    }
                }
                return true
            }
        }

        private fun drainRemaining() {
            val remaining = synchronized(lock) {
                val out = ArrayList<DroppedEvent>(items)
                items.clear()
                out
            }
            if (remaining.isEmpty()) return
            for (dropped in remaining) {
                reportError(dropped.event, null, IllegalStateException("ilogger: dead-letter event lost during shutdown"))
            }
            // The discarded events are resolved; without this a failed worker
            // keeps `submitted` ahead of `completed` and close waits out its
            // whole timeout for an event that will never be processed.
            markCompleted(remaining.size)
        }

        fun join(deadlineNanos: Long) {
            val worker = job ?: return
            runBlocking {
                withTimeoutOrNull(remainingMillis(deadlineNanos)) { worker.join() }
            }
        }

        fun cancel() {
            workerScope.cancel()
        }

        suspend fun flushSink() {
            try {
                sink.flush()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                reportError(null, null, error)
            }
        }

        fun closeSink(failures: MutableList<SinkFailure>) {
            try {
                sink.close()
            } catch (error: Throwable) {
                failures += SinkFailure(sink.javaClass.name, error)
                reportError(null, null, error)
            }
        }
    }

    // ------------------------------------------------------------------
    // Flush, idle and close
    // ------------------------------------------------------------------

    private fun routesFor(category: String?): List<BindingPipeline> {
        if (pipelines.isEmpty()) return emptyList()
        val result = ArrayList<BindingPipeline>(4)
        for (pipeline in pipelines) {
            val routing = pipeline.binding.category
            if (routing == null || routing == category) result.add(pipeline)
        }
        return result
    }

    private fun captureWatermarks(): List<Pair<BindingPipeline, Long>> = synchronized(admissionLock) {
        pipelines.map { it to it.submittedCount() }
    }

    private fun awaitPipelines(
        targets: List<Pair<BindingPipeline, Long>>,
        deadlineNanos: Long,
    ): Boolean {
        for ((pipeline, target) in targets) {
            val reached = if (config.sinkConcurrency > 1) {
                pipeline.awaitFullyIdle(deadlineNanos)
            } else {
                pipeline.awaitCompleted(target, deadlineNanos)
            }
            if (!reached) return false
        }
        return true
    }

    private fun distinctSinks(): List<LogSink> {
        if (pipelines.isEmpty()) return emptyList()
        val seen = Collections.newSetFromMap(IdentityHashMap<LogSink, Boolean>())
        val result = ArrayList<LogSink>(pipelines.size)
        for (pipeline in pipelines) {
            if (seen.add(pipeline.binding.sink)) result.add(pipeline.binding.sink)
        }
        return result
    }

    private suspend fun flushDistinctSinks() {
        for (sink in distinctSinks()) {
            try {
                sink.flush()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                reportError(null, sink, error)
            }
        }
    }

    override fun flush() {
        flush(config.closeTimeoutMillis)
    }

    override fun flush(timeoutMillis: Long): FlushReport {
        if (closedFlag.get()) {
            val report = lastCloseReport
            if (report != null) {
                return FlushReport(
                    timedOut = report.timedOut,
                    accepted = report.accepted,
                    delivered = report.delivered,
                    pending = report.pending,
                    inFlight = report.inFlight,
                    droppedByReason = report.droppedByReason,
                )
            }
        }
        val deadline = deadlineAfter(timeoutMillis)
        var timedOut = !awaitPipelines(captureWatermarks(), deadline)
        if (!timedOut) timedOut = !awaitDeadLetters(deadline)
        val flushed = runBlocking {
            withTimeoutOrNull(remainingMillis(deadline).coerceAtLeast(1)) {
                flushDistinctSinks()
                deadLetters?.flushSink()
                Unit
            }
        }
        if (flushed == null) timedOut = true
        return FlushReport(
            timedOut = timedOut,
            accepted = acceptedAdmissions.sum(),
            delivered = deliveredDeliveries.sum(),
            pending = pendingCount(),
            inFlight = activeWrites.get(),
            droppedByReason = dropSnapshot(),
        )
    }

    override fun awaitIdle(timeoutMillis: Long): Boolean {
        if (closedFlag.get()) return pendingCount() == 0
        val deadline = deadlineAfter(timeoutMillis)
        if (!awaitPipelines(captureWatermarks(), deadline)) return false
        return awaitDeadLetters(deadline)
    }

    private fun awaitDeadLetters(deadlineNanos: Long): Boolean {
        val pipeline = deadLetters ?: return true
        return pipeline.awaitCompleted(pipeline.submittedCount(), deadlineNanos)
    }

    override fun stats(): DeliveryStats = DeliveryStats(
        accepted = acceptedAdmissions.sum(),
        delivered = deliveredDeliveries.sum(),
        pending = pendingCount(),
        inFlight = activeWrites.get(),
        droppedByReason = dropSnapshot(),
        bindings = pipelineStats(),
        truncated = truncatedEvents.sum(),
    )

    private fun pipelineStats(): List<BindingStats> = synchronized(admissionLock) {
        pipelines.map { pipeline ->
            BindingStats(
                index = pipeline.index,
                category = pipeline.binding.category,
                sink = pipeline.binding.sink.javaClass.name,
                pending = pipeline.queueSize(),
                inFlight = pipeline.inFlight(),
                accepted = pipeline.submittedCount(),
                delivered = pipeline.deliveredCount.sum(),
            )
        }
    }

    private fun pendingCount(): Int = synchronized(admissionLock) {
        var total = deadLetters?.pending() ?: 0
        for (pipeline in pipelines) total += pipeline.queueSize()
        total
    }

    private fun dropSnapshot(): Map<DropReason, Long> {
        val snapshot = EnumMap<DropReason, Long>(DropReason::class.java)
        for ((reason, adder) in dropCounts) {
            val count = adder.sum()
            if (count > 0) snapshot[reason] = count
        }
        return snapshot
    }

    private fun deadlineAfter(millis: Long): Long =
        System.nanoTime() + millis.coerceAtLeast(0) * 1_000_000L

    private fun remainingMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)

    override fun close() {
        closeAndReport()
    }

    override fun closeAndReport(): CloseReport {
        synchronized(lifecycleLock) {
            lastCloseReport?.let { return it }
            closedFlag.set(true)

            val deadline = deadlineAfter(config.closeTimeoutMillis)
            val targets = synchronized(admissionLock) {
                for (pipeline in pipelines) pipeline.closeQueue()
                pipelines.map { it to it.submittedCount() }
            }
            signalCapacityWaiters()
            // Events admitted before close may not have started their workers
            // yet (the producer can be preempted between admission and start).
            for (pipeline in pipelines) pipeline.startIfNeeded()

            var timedOut = !awaitPipelines(targets, deadline)

            if (timedOut) {
                scope.cancel()
                for (pipeline in pipelines) drainQueue(pipeline, DropReason.CLOSED, null)
            } else {
                scope.cancel()
            }
            joinWorkers(deadline)

            deadLetters?.let { pipeline ->
                pipeline.closeQueue()
                // A drop may have been queued without its worker ever starting.
                if (pipeline.pending() > 0) pipeline.startIfNeeded()
                if (!pipeline.awaitCompleted(pipeline.submittedCount(), deadline)) timedOut = true
                pipeline.cancel()
                pipeline.join(deadline)
            }

            val failures = ArrayList<SinkFailure>(2)
            runBlocking {
                withTimeoutOrNull(remainingMillis(deadline).coerceAtLeast(1)) {
                    flushDistinctSinks()
                    deadLetters?.flushSink()
                    Unit
                }
            }
            for (sink in distinctSinks()) {
                try {
                    sink.close()
                } catch (error: Throwable) {
                    failures += SinkFailure(sink.javaClass.name, error)
                    reportError(null, sink, error)
                }
            }
            deadLetters?.closeSink(failures)

            val report = CloseReport(
                timedOut = timedOut,
                accepted = acceptedAdmissions.sum(),
                delivered = deliveredDeliveries.sum(),
                pending = pendingCount(),
                inFlight = activeWrites.get(),
                droppedByReason = dropSnapshot(),
                sinkFailures = failures.toList(),
            )
            lastCloseReport = report
            return report
        }
    }

    private fun joinWorkers(deadlineNanos: Long) {
        val jobs = pipelines.flatMap { it.jobsSnapshot() }
        if (jobs.isEmpty()) return
        runBlocking {
            withTimeoutOrNull(remainingMillis(deadlineNanos).coerceAtLeast(1)) {
                jobs.forEach { it.join() }
            }
        }
    }

    private fun signalCapacityWaiters() {
        capacitySignal.close()
        synchronized(capacityMonitor) { capacityMonitor.notifyAll() }
    }

    private companion object {
        const val SOURCE_LOGGER = "ILogger"
        const val MAX_OPERATION_DECISIONS = 1024
    }
}
