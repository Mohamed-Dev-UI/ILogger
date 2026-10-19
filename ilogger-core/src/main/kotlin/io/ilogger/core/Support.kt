package io.ilogger.core

import io.ilogger.annotations.LogLevel
import kotlin.random.Random

/**
 * Injectable time source. Production uses [SystemClock]; tests can use a fixed
 * clock, making event timestamps deterministic.
 */
public fun interface LogClock {
    public fun nowMillis(): Long
}

/** System wall clock. */
public object SystemClock : LogClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Fixed clock for deterministic tests. */
public class FixedClock(private var millis: Long) : LogClock {
    public fun set(millis: Long) { this.millis = millis }
    override fun nowMillis(): Long = millis
}

/**
 * Injectable event-id generator (UUID by default). Applications may provide a
 * deterministic strategy for tests or a distributed-safe id scheme.
 */
public fun interface EventIdGenerator {
    public fun next(): String
}

/** Default id generator: random UUIDs (RFC 4122 version 4). */
public object UuidEventIdGenerator : EventIdGenerator {
    override fun next(): String = java.util.UUID.randomUUID().toString()
}

/**
 * Decides whether an event is emitted or skipped for sampling purposes.
 *
 * Guarantees around audit events: sampling is ONLY applied to levels listed in
 * the configured rates. [LogLevel.AUDIT] events are never sampled unless the
 * application explicitly configures a sub-1.0 rate for them (an explicit,
 * deliberate act). Annotation-level sampling on audit events is rejected by
 * the compiler at usage time.
 */
public fun interface LogSampler {
    public fun shouldSample(level: LogLevel, action: String): Boolean
}

/** Level-based sampler; levels not listed are never sampled (rate 1.0). */
public class LevelSampler(private val rates: Map<LogLevel, Double>, private val random: Random = Random.Default) : LogSampler {
    public constructor(vararg rates: Pair<LogLevel, Double>, random: Random = Random.Default) :
        this(rates.toMap(), random)

    init {
        rates.values.forEach { rate ->
            require(rate in 0.0..1.0) { "Sampling rate must be within [0..1], got $rate" }
        }
    }

    override fun shouldSample(level: LogLevel, action: String): Boolean {
        val rate = rates[level] ?: return true
        return random.nextDouble() < rate
    }
}

/**
 * What to do when events are produced faster than the dispatch pipeline can
 * accept them. Behavior applies to the asynchronous pipeline only; INLINE
 * dispatch is synchronous by definition.
 */
public enum class OverflowPolicy {
    /** Discard the oldest queued event, make room for the new one (preserves recency). */
    DROP_OLDEST,

    /** Discard the event attempting to enqueue (preserves order). */
    DROP_NEWEST,

    /** Suspend the producing operation until space is available. */
    BLOCK,

    /** Fail the producing operation with an exception. Never silently discards. */
    FAIL,
}

/**
 * What to do when a sink fails to accept an event.
 */
public enum class ErrorMode {
    /** Drop the event silently (after reporting via `onDispatchError`). */
    DROP,

    /** Retry with bounded exponential backoff; drop when attempts are exhausted. */
    RETRY,

    /**
     * Propagate the failure to the producer (fails the instrumented operation).
     * Only meaningful for INLINE dispatch; downgraded to RETRY-then-DROP inside
     * the asynchronous worker, where there is no producer to fail.
     */
    PROPAGATE,
}
