package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.sink.ConsoleLogSink
import kotlinx.coroutines.CoroutineDispatcher

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
    public val errorModes: Map<LogLevel, ErrorMode>,
    public val retryMaxAttempts: Int,
    public val retryBaseBackoffMillis: Long,
    public val onDispatchError: ((event: LogEvent?, sink: LogSink?, error: Throwable) -> Unit)?,
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

    /** EVENTS per batch for [DispatchMode.BUFFERED] dispatch. */
    public var batchSize: Int = 128

    /** Batch max-age: how long a partial batch may wait for more events. */
    public var flushIntervalMillis: Long = 250

    /** Dispatcher for the pipeline worker; defaults to a single-threaded view of [Dispatchers.Default] pattern. */
    public var workerDispatcher: CoroutineDispatcher? = null

    /** Maximum time [ILogger.close] waits for in-flight events to drain. */
    public var closeTimeoutMillis: Long = 5_000

    /** Optional sampling; audit levels are never sampled unless configured explicitly. */
    public var sampler: LogSampler? = null

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

    /** Adds a sink binding. Use [category] to route only that category; default is catch-all. */
    public fun sink(sink: LogSink, category: String? = null) {
        sinksInternal += SinkBinding(sink, category)
    }

    /**
     * Configures diagnostic sampling per level; levels not listed are always
     * sampled (rate 1.0). This call replaces any previously set sampler.
     */
    public fun sampling(vararg rates: Pair<LogLevel, Double>) {
        sampler = LevelSampler(*rates)
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
        check(batchSize in 1..bufferSize) { "batchSize must be within 1..bufferSize" }
        check(flushIntervalMillis >= 0) { "flushIntervalMillis must be >= 0" }
        check(closeTimeoutMillis >= 0) { "closeTimeoutMillis must be >= 0" }
        check(retryMaxAttempts >= 0) { "retryMaxAttempts must be >= 0" }
        check(retryBaseBackoffMillis >= 0) { "retryBaseBackoffMillis must be >= 0" }
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
            errorModes = errorModesInternal.toMap(),
            retryMaxAttempts = retryMaxAttempts,
            retryBaseBackoffMillis = retryBaseBackoffMillis,
            onDispatchError = onDispatchError,
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

    /** `true` when [level] events will pass the min-level floor (sampling excluded). */
    public fun isEnabled(level: LogLevel): Boolean

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
     * Non-suspend sibling of [record] for contexts where suspending is not
     * possible (synchronous DAOs, callbacks). Ambient context comes from the
     * thread-local bridge only. [DispatchMode.INLINE] is unsupported here —
     * use the suspend [record] for synchronous-delivery semantics.
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

    /** Best-effort flush of async pipelines and sinks (bounded by close timeout). */
    public fun flush()

    /** Drains async pipelines (bounded), flushes and closes all sinks. Idempotent. */
    override fun close()

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

    override fun flush() {}
    override fun close() {}
}
