package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.sink.ConsoleLogSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.reflect.KClass

/**
 * A binding that routes events to a sink.
 *
 * @param sink receiver of routed events.
 * @param category only receives events with this [LogEvent.category]. `null`
 *   binds as *catch-all*: it receives events of every category (in addition to
 *   any same-category binding).
 */
public class SinkBinding(public val sink: LogSink, public val category: String? = null)

/**
 * Immutable, validated runtime configuration for an [ILogger]. Built with
 * [ILoggerBuilder]; configuration precedence is documented in the project
 * README (operation override → module configuration → application
 * configuration → library defaults).
 */
public class ILoggerConfig internal constructor(
    public val enabled: Boolean,
    public val minLevel: LogLevel,
    public val clock: LogClock,
    public val idGenerator: EventIdGenerator,
    public val actorProvider: ActorProvider,
    public val bindings: List<SinkBinding>,
    public val dispatch: DispatchMode,
    public val overflow: OverflowPolicy,
    public val bufferSize: Int,
    public val batchSize: Int,
    public val flushIntervalMillis: Long,
    public val workerDispatcher: CoroutineDispatcher?,
    public val closeTimeoutMillis: Long,
    public val sampler: LogSampler?,
    public val contextualSampler: ContextualSampler?,
    public val auditSampling: Boolean,
    public val strictMode: StrictMode,
    public val strictMaxStringLength: Int,
    public val strictMaxEntries: Int,
    public val strictMaxEventBytes: Int,
    public val errorModes: Map<LogLevel, ErrorMode>,
    public val retryMaxAttempts: Int,
    public val retryBaseBackoffMillis: Long,
    public val onDispatchError: ((event: LogEvent?, sink: LogSink?, error: Throwable) -> Unit)?,
    public val sinkConcurrency: Int,
    public val blockTimeoutMillis: Long,
    public val deadLetterSink: DeadLetterSink?,
    /** Runtime ValueAdapter registry consulted at event materialization. */
    public val valueAdapters: ValueAdapterRegistry,
    /**
     * Extractor instances used by generated wrappers, keyed by the class named
     * in `LogMeta(extractor = ...)`. Configured through [ILoggerBuilder.extractor].
     */
    public val extractors: ExtractorRegistry,
    public val onDrop: ((dropped: DroppedEvent) -> Unit)?,
    /**
     * Hard payload bounds enforced before sampling and allocation, or `null`
     * when no limits are configured. See [EventLimits].
     */
    public val limits: EventLimits?,
    /**
     * Runtime redaction policy applied after limits and before sampling, or
     * `null` when no redaction is configured. See [RedactionPolicy].
     */
    public val redaction: RedactionPolicy?,
    /**
     * Admission-time metadata enrichers in registration order. Empty by
     * default; see [LogEventEnricher] for merge and failure rules.
     */
    public val enrichers: List<LogEventEnricher> = emptyList(),
)

/**
 * Fluent builder producing [ILoggerConfig]. Every field has a documented
 * default; applications typically set sinks and little else.
 */
public class ILoggerBuilder {
    /** Master runtime switch; events can also be disabled per logger later. */
    public var enabled: Boolean = true

    /**
     * Minimum diagnostic level for `trace/debug/info/warn/error` calls.
     * [LogLevel.AUDIT] events are structurally above this floor: audit traffic
     * is only filtered with explicit audit configuration, never by accident.
     */
    public var minLevel: LogLevel = LogLevel.TRACE

    public var clock: LogClock = SystemClock
    public var idGenerator: EventIdGenerator = UuidEventIdGenerator
    public var actorProvider: ActorProvider = ActorProvider { null }

    /**
     * Pipelines and their routing.
     *
     * Defaults: first `sink(...)` binding with `category = null` becomes
     * catch-all.
     */
    private var sinksInternal: MutableList<SinkBinding> = mutableListOf()

    /**
     * Engine-wide asynchronous dispatch mode for events that do not request
     * [DispatchMode.INLINE].
     */
    public var dispatch: DispatchMode = DispatchMode.BUFFERED

    /** Backpressure policy of the async pipeline. */
    public var overflow: OverflowPolicy = OverflowPolicy.DROP_NEWEST

    /**
     * Capacity of the async pipeline, in events. It is finite by design:
     * memory never grows unboundedly under producer floods.
     */
    public var bufferSize: Int = 4096

    /** EVENTS per batch for [DispatchMode.BUFFERED] dispatch; capped by the queue contents. */
    public var batchSize: Int = 128

    /** Batch max-age: how long a partial batch may wait for more events. */
    public var flushIntervalMillis: Long = 250

    /** Dispatcher for the pipeline worker; defaults to a single-threaded view of [Dispatchers.Default] pattern. */
    public var workerDispatcher: CoroutineDispatcher? = null

    /** Maximum time [ILogger.close] waits for in-flight events to drain. */
    public var closeTimeoutMillis: Long = 5_000

    /** Optional sampling; audit levels are never sampled unless configured explicitly. */
    public var sampler: LogSampler? = null

    /** Preferred sampler SPI; receives the real action, category, outcome and metadata. */
    public var contextualSampler: ContextualSampler? = null

    /**
     * Allows the configured sampler to decide audit events. Off by default:
     * audit is the product and is only sampled as a deliberate act.
     */
    public var auditSampling: Boolean = false

    /**
     * Runtime payload validation policy. [StrictMode.OFF] by default; reference
     * applications should use [StrictMode.WARN] in debug builds.
     */
    public var strictMode: StrictMode = StrictMode.OFF

    /** Maximum length of metadata/actor/entity strings in strict mode. */
    public var strictMaxStringLength: Int = 1_024

    /** Maximum entries in metadata, actor attributes, changes, or nested collections. */
    public var strictMaxEntries: Int = 64

    /** Approximate maximum payload size (string characters + 8 bytes per scalar). */
    public var strictMaxEventBytes: Int = 65_536

    /** Per-level sink failure behavior; falls back to sensible defaults. */
    private var errorModesInternal: MutableMap<LogLevel, ErrorMode> = mutableMapOf(
        LogLevel.AUDIT to ErrorMode.RETRY,
        LogLevel.ERROR to ErrorMode.RETRY,
        LogLevel.WARN to ErrorMode.DROP,
        LogLevel.INFO to ErrorMode.DROP,
        LogLevel.DEBUG to ErrorMode.DROP,
        LogLevel.TRACE to ErrorMode.DROP,
    )

    public var retryMaxAttempts: Int = 3
    public var retryBaseBackoffMillis: Long = 50

    /** Notified of pipeline-level failures that could not reach a producer. */
    public var onDispatchError: ((event: LogEvent?, sink: LogSink?, error: Throwable) -> Unit)? = null

    /**
     * Number of workers per binding. The default of 1 preserves per-sink
     * ordering; values above 1 process events of one binding concurrently
     * (ordering becomes best-effort) and are intended for stateless sinks that
     * are safe under concurrent writes.
     */
    public var sinkConcurrency: Int = 1

    /**
     * Upper bound for [OverflowPolicy.BLOCK] admission and for the fallback
     * path of [ILogger.recordBlocking]. A saturated queue never blocks a
     * producer longer than this.
     */
    public var blockTimeoutMillis: Long = 5_000

    /** Notified synchronously for every lost event, with its [DropReason]. */
    public var onDrop: ((dropped: DroppedEvent) -> Unit)? = null

    /**
     * Hard payload bounds (string length, entry counts, actor attributes,
     * failure frames, approximate bytes). `null` (the default) disables limit
     * enforcement; see [EventLimits] for production recommendations.
     */
    public var limits: EventLimits? = null

    /**
     * Runtime redaction rules applied to metadata, change values and actor
     * attributes after limits and before sampling. `null` (the default) makes
     * redaction a zero-cost pass-through; see [RedactionPolicy].
     */
    public var redaction: RedactionPolicy? = null

    private var deadLetterSinkInternal: DeadLetterSink? = null
    private var valueAdaptersInternal = ValueAdapterRegistry()
    private var extractorsInternal = ExtractorRegistry()
    private var enrichersInternal: MutableList<LogEventEnricher> = mutableListOf()

    /**
     * Registers an admission-time metadata [enricher]. Enrichers see the
     * producer thread's ambient [LogContext] and run before limits, redaction,
     * sampling and delivery, so trace/span ids stay attached to the event
     * across asynchronous dispatch.
     *
     * Explicit event metadata wins over enricher output; among enrichers the
     * first registration wins. Throwing enrichers are contained and reported
     * through [onDispatchError].
     */
    public fun enricher(enricher: LogEventEnricher) {
        enrichersInternal += enricher
    }

    /** Adds a sink binding. Use [category] to route only that category; default is catch-all. */
    public fun sink(sink: LogSink, category: String? = null) {
        sinksInternal += SinkBinding(sink, category)
    }

    /**
     * Registers a [ValueAdapter] applied at event materialization: metadata
     * and [FieldChange] values whose exact runtime class matches [type] are
     * rendered through [adapter] before validation and delivery.
     */
    public fun <T : Any> valueAdapter(type: Class<T>, adapter: ValueAdapter<T>) {
        valueAdaptersInternal.register(type, adapter)
    }

    public fun <T : Any> valueAdapter(type: KClass<T>, adapter: ValueAdapter<T>) {
        valueAdaptersInternal.register(type, adapter)
    }

    /**
     * Registers the factory for an extractor class named by
     * `LogMeta(extractor = ...)`. The factory runs once, on first use, and its
     * instance is shared by every generated wrapper of this logger.
     *
     * This is the seam for dependency injection: Hilt/Koin users expose the
     * instance through an entry point and hand it over here — core has no DI
     * dependency. Extractors declared with `inject = true` have no fallback
     * and must be registered.
     */
    public fun <T : Any> extractor(type: KClass<T>, factory: () -> T) {
        extractorsInternal.register(type, factory)
    }

    /** Class-based overload for Java callers. */
    public fun <T : Any> extractor(type: Class<T>, factory: () -> T) {
        extractorsInternal.register(type, factory)
    }

    /** Registers an already-constructed extractor instance. */
    public fun <T : Any> extractor(type: KClass<T>, instance: T) {
        extractorsInternal.register(type, instance)
    }

    /** Class-based overload for Java callers. */
    public fun <T : Any> extractor(type: Class<T>, instance: T) {
        extractorsInternal.register(type, instance)
    }

    /** Routes every lost event (with its reason) to [sink] on a background pipeline. */
    public fun deadLetter(sink: DeadLetterSink) {
        deadLetterSinkInternal = sink
    }

    /** Convenience: uses an ordinary [LogSink] as dead-letter destination. */
    public fun deadLetter(sink: LogSink) {
        deadLetterSinkInternal = DeadLetterSink.of(sink)
    }

    /**
     * Configures diagnostic sampling per level; levels not listed are always
     * sampled (rate 1.0). This call replaces any previously set sampler.
     *
     * Listing [LogLevel.AUDIT] also enables [auditSampling]: an explicit audit
     * rate is the deliberate act the default protects against.
     */
    public fun sampling(vararg rates: Pair<LogLevel, Double>) {
        sampler = LevelSampler(*rates)
        contextualSampler = null
        if (rates.any { (level, _) -> level == LogLevel.AUDIT }) auditSampling = true
    }

    /** Configures a context-aware sampler; replaces any previously set sampler. */
    public fun sampling(sampler: ContextualSampler) {
        contextualSampler = sampler
        this.sampler = null
    }

    /** Sets the failure behavior for events of [level]. */
    public fun onErrorMode(level: LogLevel, mode: ErrorMode) {
        errorModesInternal[level] = mode
    }

    /**
     * Builds the config, enforcing documented invariants.
     */
    internal fun buildConfig(): ILoggerConfig {
        check(bufferSize > 0) { "bufferSize must be positive" }
        check(batchSize >= 1) { "batchSize must be >= 1" }
        check(flushIntervalMillis >= 0) { "flushIntervalMillis must be >= 0" }
        check(closeTimeoutMillis >= 0) { "closeTimeoutMillis must be >= 0" }
        check(retryMaxAttempts >= 0) { "retryMaxAttempts must be >= 0" }
        check(retryBaseBackoffMillis >= 0) { "retryBaseBackoffMillis must be >= 0" }
        check(sinkConcurrency >= 1) { "sinkConcurrency must be >= 1" }
        check(blockTimeoutMillis >= 0) { "blockTimeoutMillis must be >= 0" }
        check(strictMaxStringLength > 0) { "strictMaxStringLength must be positive" }
        check(strictMaxEntries > 0) { "strictMaxEntries must be positive" }
        check(strictMaxEventBytes > 0) { "strictMaxEventBytes must be positive" }
        return ILoggerConfig(
            enabled = enabled,
            minLevel = minLevel,
            clock = clock,
            idGenerator = idGenerator,
            actorProvider = actorProvider,
            bindings = sinksInternal.toList(),
            dispatch = dispatch,
            overflow = overflow,
            bufferSize = bufferSize,
            batchSize = batchSize,
            flushIntervalMillis = flushIntervalMillis,
            workerDispatcher = workerDispatcher,
            closeTimeoutMillis = closeTimeoutMillis,
            sampler = sampler,
            contextualSampler = contextualSampler,
            auditSampling = auditSampling,
            strictMode = strictMode,
            strictMaxStringLength = strictMaxStringLength,
            strictMaxEntries = strictMaxEntries,
            strictMaxEventBytes = strictMaxEventBytes,
            errorModes = errorModesInternal.toMap(),
            retryMaxAttempts = retryMaxAttempts,
            retryBaseBackoffMillis = retryBaseBackoffMillis,
            onDispatchError = onDispatchError,
            sinkConcurrency = sinkConcurrency,
            blockTimeoutMillis = blockTimeoutMillis,
            deadLetterSink = deadLetterSinkInternal,
            onDrop = onDrop,
            valueAdapters = valueAdaptersInternal,
            extractors = extractorsInternal,
            limits = limits,
            redaction = redaction,
            enrichers = enrichersInternal.toList(),
        )
    }
}

/**
 * The structured logging + audit engine object of a host application.
 *
 * There are two ways to instantiate:
 *
 * - explicit instances: `val logger = ILogger.create { sink(ConsoleLogSink()) }`
 *   (largely preferred — framework independent and testable);
 * - a process-wide default via [configure]/[default] for simple hosts.
 *
 * ## Lifecycle
 *
 * Async/buffered workers are managed: the pipeline starts lazily on first
 * asynchronous event and shuts down deterministically in [close], draining
 * pending events within the configured close timeout.
 */
public interface ILogger : AutoCloseable {

    /** Runtime master switch: `false` skips all dispatch (statistically cheapest check). */
    public var enabled: Boolean

    /**
     * Extractor instances for generated wrappers. [DefaultILogger] returns the
     * logger's configured registry; the default implementation returns a fresh,
     * empty registry, so generated code falls back to no-argument constructors.
     */
    public val extractors: ExtractorRegistry
        get() = ExtractorRegistry()

    /** `true` when [level] events will pass the min-level floor (sampling excluded). */
    public fun isEnabled(level: LogLevel): Boolean

    /**
     * Cheap admission pre-check for producers that build expensive payloads:
     * combines the master switch, the min-level floor and the configured
     * sampler (with [Outcome.SUCCESS] and empty metadata as the sampling
     * input). `false` means recording would drop the event.
     *
     * Correlated operations reuse the cached per-operation decision, so a
     * `true` result here agrees with the following `record` call.
     */
    public fun isLoggable(level: LogLevel, action: String, category: String? = null): Boolean =
        isEnabled(level)

    public fun trace(message: String, vararg fields: Pair<String, Any?>)
    public fun debug(message: String, vararg fields: Pair<String, Any?>)
    public fun info(message: String, vararg fields: Pair<String, Any?>)
    public fun warn(message: String, vararg fields: Pair<String, Any?>)
    public fun error(message: String, vararg fields: Pair<String, Any?>)

    /**
     * Records one structured audit (or high-level) event through the same
     * event/dispatch infrastructure as generated instrumentation.
     *
     * Ambiguity is fine here: everything not provided is inferred when
     * possible (ambient [LogContext] for actor/correlation/causation, then the
     * configured [ActorProvider]).
     *
     * Actor precedence: [actor] parameter → ambient [LogContext.actor] →
     * configured [ILoggerConfig.actorProvider].
     *
     * @param action canonical action code, e.g. `"task.created"`.
     * @param level defaults to [LogLevel.AUDIT].
     * @param dispatch dispatch mode; [DispatchMode.DEFAULT] uses config.
     */
    public suspend fun record(
        action: String,
        level: LogLevel = LogLevel.AUDIT,
        entity: String? = null,
        entityId: String? = null,
        actor: Actor? = null,
        outcome: Outcome = Outcome.SUCCESS,
        failure: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap(),
        changes: List<FieldChange> = emptyList(),
        source: String? = null,
        category: String? = null,
        actionVersion: Int = 1,
        dispatch: DispatchMode = DispatchMode.DEFAULT,
        sampling: Double = -1.0,
    )

    /**
     * Draft-based sibling of [record]; every field except the action is
     * optional. The default implementation delegates to the parameter-based
     * overload, so custom loggers can override either shape.
     */
    public suspend fun record(draft: LogEventDraft) {
        record(
            action = draft.action,
            level = draft.level,
            entity = draft.entity,
            entityId = draft.entityId,
            actor = draft.actor,
            outcome = draft.outcome,
            failure = draft.failure,
            metadata = draft.metadata,
            changes = draft.changes,
            source = draft.source,
            category = draft.category,
            actionVersion = draft.actionVersion,
            dispatch = draft.dispatch,
            sampling = draft.sampling,
        )
    }

    /**
     * Non-suspend sibling of [record] for contexts where suspending is not
     * possible (synchronous DAOs, callbacks). Ambient context comes from the
     * thread-local bridge only.
     *
     * [DispatchMode.INLINE] is supported with a bounded blocking wait
     * (`blockTimeoutMillis`): the event is written before this call returns,
     * or dropped with [DropReason.SINK_FAILURE] if the sink does not finish in
     * time. Prefer [recordBlocking] when you need an explicit timeout and an
     * admission result.
     */
    public fun recordNow(
        action: String,
        level: LogLevel = LogLevel.AUDIT,
        entity: String? = null,
        entityId: String? = null,
        actor: Actor? = null,
        outcome: Outcome = Outcome.SUCCESS,
        failure: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap(),
        changes: List<FieldChange> = emptyList(),
        source: String? = null,
        category: String? = null,
        actionVersion: Int = 1,
        dispatch: DispatchMode = DispatchMode.ASYNC,
        sampling: Double = -1.0,
    )

    /** Draft-based sibling of [recordNow]; delegates to the parameter overload by default. */
    public fun recordNow(draft: LogEventDraft) {
        recordNow(
            action = draft.action,
            level = draft.level,
            entity = draft.entity,
            entityId = draft.entityId,
            actor = draft.actor,
            outcome = draft.outcome,
            failure = draft.failure,
            metadata = draft.metadata,
            changes = draft.changes,
            source = draft.source,
            category = draft.category,
            actionVersion = draft.actionVersion,
            dispatch = draft.dispatch,
            sampling = draft.sampling,
        )
    }

    /**
     * Recording with an explicit wall-clock budget, safe from synchronous code
     * (DAOs, callbacks, `main`). Unlike [recordNow], this call may deliver
     * inline: with [DispatchMode.INLINE] the event is written before returning;
     * with asynchronous modes it is admitted to the per-sink pipelines.
     *
     * @return `true` when the event was delivered (INLINE) or accepted by every
     *   matched pipeline; `false` when it was dropped (sampled, suppressed,
     *   overflow, closed, sink failure). The default implementation delegates
     *   to [recordNow] and therefore ignores [timeoutMillis].
     */
    public fun recordBlocking(
        action: String,
        level: LogLevel = LogLevel.AUDIT,
        entity: String? = null,
        entityId: String? = null,
        actor: Actor? = null,
        outcome: Outcome = Outcome.SUCCESS,
        failure: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap(),
        changes: List<FieldChange> = emptyList(),
        source: String? = null,
        category: String? = null,
        actionVersion: Int = 1,
        dispatch: DispatchMode = DispatchMode.ASYNC,
        sampling: Double = -1.0,
        timeoutMillis: Long = 5_000,
    ): Boolean {
        recordNow(
            action = action,
            level = level,
            entity = entity,
            entityId = entityId,
            actor = actor,
            outcome = outcome,
            failure = failure,
            metadata = metadata,
            changes = changes,
            source = source,
            category = category,
            actionVersion = actionVersion,
            dispatch = dispatch,
            sampling = sampling,
        )
        return true
    }

    /**
     * Draft-based sibling of [recordBlocking]; delegates to the parameter
     * overload by default so custom loggers keep their blocking semantics.
     */
    public fun recordBlocking(draft: LogEventDraft, timeoutMillis: Long = 5_000): Boolean =
        recordBlocking(
            action = draft.action,
            level = draft.level,
            entity = draft.entity,
            entityId = draft.entityId,
            actor = draft.actor,
            outcome = draft.outcome,
            failure = draft.failure,
            metadata = draft.metadata,
            changes = draft.changes,
            source = draft.source,
            category = draft.category,
            actionVersion = draft.actionVersion,
            dispatch = draft.dispatch,
            sampling = draft.sampling,
            timeoutMillis = timeoutMillis,
        )

    /**
     * True when this logger currently has at least one sink binding and is
     * enabled and open — a cheap pre-check before building an expensive
     * payload. Default: [enabled].
     */
    public fun hasSinks(): Boolean = enabled

    /**
     * `true` when no matched pipeline still has queue capacity. Use it to shed
     * diagnostic load before the engine has to drop events.
     *
     * Default: `false` (custom loggers are assumed to accept everything).
     */
    public fun isBacklogged(): Boolean = false

    /**
     * Free queue slots on the most constrained binding, or [Int.MAX_VALUE] for
     * unbounded custom loggers. `0` means the next asynchronous event will hit
     * the overflow policy.
     *
     * Default: [Int.MAX_VALUE] while [enabled], otherwise `0`.
     */
    public fun availableCapacity(): Int = if (enabled) Int.MAX_VALUE else 0

    /**
     * Best-effort, non-suspend recording for high-volume producers.
     *
     * Never throws and never blocks:
     * - full queues are dropped regardless of the configured [OverflowPolicy]
     *   (`BLOCK` and `FAIL` do not wait or raise);
     * - strict-mode `THROW` rejects return `false`;
     * - `INLINE` (per call or configured) writes only to sinks implementing
     *   [DirectLogSink] and otherwise returns `false`.
     *
     * @return `true` when every matched pipeline accepted the event (or every
     *   matched direct sink wrote it); `false` on any drop.
     */
    public fun tryRecord(
        action: String,
        level: LogLevel = LogLevel.AUDIT,
        entity: String? = null,
        entityId: String? = null,
        actor: Actor? = null,
        outcome: Outcome = Outcome.SUCCESS,
        failure: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap(),
        changes: List<FieldChange> = emptyList(),
        source: String? = null,
        category: String? = null,
        actionVersion: Int = 1,
        dispatch: DispatchMode = DispatchMode.DEFAULT,
        sampling: Double = -1.0,
    ): Boolean {
        if (!enabled) return false
        recordNow(
            action = action,
            level = level,
            entity = entity,
            entityId = entityId,
            actor = actor,
            outcome = outcome,
            failure = failure,
            metadata = metadata,
            changes = changes,
            source = source,
            category = category,
            actionVersion = actionVersion,
            dispatch = dispatch,
            sampling = sampling,
        )
        return true
    }

    /** Draft-based sibling of [tryRecord]. */
    public fun tryRecord(draft: LogEventDraft): Boolean = tryRecord(
        action = draft.action,
        level = draft.level,
        entity = draft.entity,
        entityId = draft.entityId,
        actor = draft.actor,
        outcome = draft.outcome,
        failure = draft.failure,
        metadata = draft.metadata,
        changes = draft.changes,
        source = draft.source,
        category = draft.category,
        actionVersion = draft.actionVersion,
        dispatch = draft.dispatch,
        sampling = draft.sampling,
    )

    /**
     * Unit-returning convenience sibling of [tryRecord] for fire-and-forget
     * call sites that do not care about the admission result.
     */
    public fun recordOrDrop(
        action: String,
        level: LogLevel = LogLevel.AUDIT,
        entity: String? = null,
        entityId: String? = null,
        actor: Actor? = null,
        outcome: Outcome = Outcome.SUCCESS,
        failure: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap(),
        changes: List<FieldChange> = emptyList(),
        source: String? = null,
        category: String? = null,
        actionVersion: Int = 1,
        dispatch: DispatchMode = DispatchMode.DEFAULT,
        sampling: Double = -1.0,
    ): Unit {
        tryRecord(
            action = action,
            level = level,
            entity = entity,
            entityId = entityId,
            actor = actor,
            outcome = outcome,
            failure = failure,
            metadata = metadata,
            changes = changes,
            source = source,
            category = category,
            actionVersion = actionVersion,
            dispatch = dispatch,
            sampling = sampling,
        )
    }

    /** Draft-based sibling of [recordOrDrop]. */
    public fun recordOrDrop(draft: LogEventDraft): Unit {
        tryRecord(draft)
    }

    /**
     * Waits until every event enqueued before this call has been processed by
     * its pipelines, then flushes every distinct sink. Bounded by the
     * configured close timeout. See [flush] for the reporting overload.
     */
    public fun flush()

    /**
     * True flush barrier with an explicit timeout. Waits for events enqueued
     * before the call (watermark), the dead-letter pipeline, and all sinks.
     * Returns a snapshot even when the timeout expires.
     *
     * The default implementation delegates to [flush] and returns an empty
     * report; implementors that track delivery should override it.
     */
    public fun flush(timeoutMillis: Long): FlushReport {
        flush()
        return FlushReport(
            timedOut = false,
            accepted = 0,
            delivered = 0,
            pending = 0,
            inFlight = 0,
            droppedByReason = emptyMap(),
        )
    }

    /**
     * Waits until every event enqueued before this call has been processed
     * (delivered or explicitly dropped). Does not flush sinks. Returns `false`
     * on timeout. Default implementation: returns `true` immediately.
     */
    public fun awaitIdle(timeoutMillis: Long = 5_000): Boolean = true

    /** Point-in-time delivery counters. */
    public fun stats(): DeliveryStats = DeliveryStats.EMPTY

    /** Drains async pipelines (bounded), flushes and closes all sinks. Idempotent. */
    override fun close()

    /**
     * Closes the logger and returns a delivery report. Idempotent: subsequent
     * calls return the report produced by the first close. The default
     * implementation delegates to [close] and returns an empty report.
     */
    public fun closeAndReport(): CloseReport {
        close()
        return CloseReport(
            timedOut = false,
            accepted = 0,
            delivered = 0,
            pending = 0,
            inFlight = 0,
            droppedByReason = emptyMap(),
            sinkFailures = emptyList(),
        )
    }

    public companion object {

        /**
         * Creates an independent [ILogger] instance. Independent loggers give
         * applications full control over lifecycle and wiring.
         */
        public fun create(builder: ILoggerBuilder.() -> Unit): ILogger =
            DefaultILogger(ILoggerBuilder().apply(builder).buildConfig())

        /**
         * Creates an [ILogger] and installs it as the process-wide
         * [default]. Calling again replaces the default; the replaced
         * instance is NOT closed automatically.
         */
        public fun configure(builder: ILoggerBuilder.() -> Unit): ILogger =
            create(builder).also { defaultRef.set(it) }

        /** Explicitly sets (or removes with `null`) the default logger. */
        public fun setDefault(logger: ILogger?) {
            defaultRef.set(logger)
        }

        /**
         * The process-wide default logger: the last [configure]d instance, or
         * — when nothing was configured — a console logger with INLINE
         * dispatch (no background threads).
         */
        public val default: ILogger
            get() {
                defaultRef.get()?.let { return it }
                val fallback = create {
                    sink(ConsoleLogSink())
                    dispatch = DispatchMode.INLINE
                }
                defaultRef.compareAndSet(null, fallback)
                return defaultRef.get() ?: fallback
            }

        /** A permanently disabled logger; useful to explicitly opt out. */
        public val disabled: ILogger = DisabledILogger

        private val defaultRef = java.util.concurrent.atomic.AtomicReference<ILogger?>(null)
    }
}

/** Logger that drops everything; also the shape for compile-time disabled logging. */
internal object DisabledILogger : ILogger {

    @Volatile
    override var enabled: Boolean = false

    override fun isEnabled(level: LogLevel): Boolean = false

    override fun trace(message: String, vararg fields: Pair<String, Any?>) {}
    override fun debug(message: String, vararg fields: Pair<String, Any?>) {}
    override fun info(message: String, vararg fields: Pair<String, Any?>) {}
    override fun warn(message: String, vararg fields: Pair<String, Any?>) {}
    override fun error(message: String, vararg fields: Pair<String, Any?>) {}

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
    ) {}

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
    ) {}

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
    ): Boolean = false

    override fun flush() {}
    override fun close() {}
}
