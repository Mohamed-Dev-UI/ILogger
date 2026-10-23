package io.ilogger.core

import io.ilogger.annotations.LogLevel
import java.util.concurrent.atomic.LongAdder
import kotlin.math.ceil
import kotlin.math.min
import kotlin.random.Random

/**
 * Snapshot of a sampler's admission counters.
 *
 * [considered] counts events the sampler was asked about, [sampled] counts
 * events it admitted; [skipped] is the volume the sampler shed. Audit events
 * are invisible to these counters: both shipped limiters admit them
 * unconditionally and never record them as considered.
 */
public data class SamplingStats(
    public val considered: Long,
    public val sampled: Long,
    public val currentRate: Double,
    public val observedPerSecond: Double = 0.0,
    public val buckets: Int = 0,
) {
    /** Events declined by this sampler (`considered - sampled`). */
    public val skipped: Long
        get() = considered - sampled
}

/** The dimension a [TokenBucketRateLimiter] keys its buckets by. */
public enum class RateLimitScope {
    /** One bucket for every event. */
    GLOBAL,

    /** One bucket per [LogLevel]. */
    LEVEL,

    /** One bucket per action code. */
    ACTION,

    /** One bucket per category; uncategorized events share one bucket. */
    CATEGORY,

    /** One bucket per actor (type + id); events without an actor share one. */
    ACTOR,
}

/**
 * Token-bucket admission control for diagnostic and audit events, keyed by a
 * configurable [RateLimitScope].
 *
 * Each key owns an independent bucket that holds at most [burst] tokens and
 * refills at [permitsPerSecond]. A bucket starts full, so a quiet key tolerates
 * a short burst; a saturated key admits at most its configured rate. Buckets
 * are created on demand and bounded to [maxBuckets] (least-recently-used
 * eviction), so a high-cardinality key space (actions, actors) cannot grow
 * memory without limit.
 *
 * [LogLevel.AUDIT] events are never limited — they return `true` without even
 * touching a bucket. This holds even when the logger has `auditSampling = true`
 * and the sampler is consulted for audit events.
 *
 * Install it directly (`sampling(TokenBucketRateLimiter(...))`) or compose it
 * with another sampler through [CompositeSampler]:
 *
 * ```kotlin
 * ILogger.create {
 *     sampling(
 *         CompositeSampler(
 *             TokenBucketRateLimiter(permitsPerSecond = 100.0, burst = 200, scope = RateLimitScope.ACTION),
 *             AdaptiveSampler(targetEventsPerSecond = 2_000.0),
 *         ),
 *     )
 * }
 * ```
 *
 * The clock is injectable for deterministic tests; [SamplingStats] exposes
 * admitted, shed and active-bucket counts.
 */
public class TokenBucketRateLimiter(
    public val permitsPerSecond: Double,
    public val burst: Int = ceil(permitsPerSecond).toInt().coerceAtLeast(1),
    public val scope: RateLimitScope = RateLimitScope.ACTION,
    private val clock: LogClock = SystemClock,
    public val maxBuckets: Int = 1_024,
) : ContextualSampler {

    init {
        require(permitsPerSecond > 0.0) { "permitsPerSecond must be positive, got $permitsPerSecond" }
        require(burst >= 1) { "burst must be >= 1, got $burst" }
        require(maxBuckets >= 1) { "maxBuckets must be >= 1, got $maxBuckets" }
    }

    private class Bucket(var tokens: Double, var lastRefillMillis: Long)

    private val lock = Any()
    private val buckets = object : LinkedHashMap<String, Bucket>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>?): Boolean =
            size > maxBuckets
    }
    private val considered = LongAdder()
    private val admitted = LongAdder()

    override fun shouldSample(context: SamplingContext): Boolean {
        if (context.level == LogLevel.AUDIT) return true
        considered.increment()
        val now = clock.nowMillis()
        val key = keyFor(context)
        val accepted = synchronized(lock) {
            val bucket = buckets[key] ?: Bucket(burst.toDouble(), now).also { buckets[key] = it }
            refill(bucket, now)
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
        if (accepted) admitted.increment()
        return accepted
    }

    /** Current counters; safe to call concurrently. */
    public fun stats(): SamplingStats = SamplingStats(
        considered = considered.sum(),
        sampled = admitted.sum(),
        currentRate = permitsPerSecond,
        buckets = synchronized(lock) { buckets.size },
    )

    private fun refill(bucket: Bucket, now: Long) {
        if (now <= bucket.lastRefillMillis) return
        val elapsed = now - bucket.lastRefillMillis
        bucket.tokens = min(burst.toDouble(), bucket.tokens + elapsed * permitsPerSecond / 1_000.0)
        bucket.lastRefillMillis = now
    }

    private fun keyFor(context: SamplingContext): String = when (scope) {
        RateLimitScope.GLOBAL -> "*"
        RateLimitScope.LEVEL -> context.level.name
        RateLimitScope.ACTION -> context.action
        RateLimitScope.CATEGORY -> context.category ?: "*"
        RateLimitScope.ACTOR -> context.actor?.let { "${it.type.name}:${it.id ?: "*"}" } ?: "*"
    }
}

/**
 * Adaptive admission control for diagnostics: normally everything passes, and
 * sustained overload is shed proportionally.
 *
 * Once per [windowMillis] the sampler compares the offered diagnostic volume
 * with [targetEventsPerSecond] and sets the admission probability to
 * `target / observed` (never below [minRate]). A token bucket refilled at the
 * target rate caps admissions at all times, including the very first window,
 * so a 100k-events/s flood cannot slip through before the first measurement.
 * When the offered volume returns below the target, the probability recovers
 * to 1.0 on the next window.
 *
 * [LogLevel.AUDIT] events always return `true`, are not counted as offered
 * volume, and never consume tokens: adaptive shedding applies to diagnostics
 * only, so audit flow is structurally unaffected by a diagnostic flood.
 *
 * Randomness is injectable ([random]) and time is injectable ([clock]); both
 * together make tests deterministic.
 */
public class AdaptiveSampler(
    public val targetEventsPerSecond: Double,
    private val clock: LogClock = SystemClock,
    private val random: Random = Random.Default,
    public val windowMillis: Long = 1_000,
    public val minRate: Double = 0.0,
) : ContextualSampler {

    init {
        require(targetEventsPerSecond > 0.0) {
            "targetEventsPerSecond must be positive, got $targetEventsPerSecond"
        }
        require(windowMillis > 0) { "windowMillis must be positive, got $windowMillis" }
        require(minRate in 0.0..1.0) { "minRate must be within [0..1], got $minRate" }
    }

    private val burst: Int = ceil(targetEventsPerSecond * windowMillis / 1_000.0)
        .toInt().coerceAtLeast(1)

    private val lock = Any()
    private var windowStartMillis = Long.MIN_VALUE
    private var windowOffered = 0L
    private var keepRate = 1.0
    private var lastObservedPerSecond = 0.0
    private var tokens = burst.toDouble()
    private var lastRefillMillis = Long.MIN_VALUE
    private val considered = LongAdder()
    private val admitted = LongAdder()

    override fun shouldSample(context: SamplingContext): Boolean {
        if (context.level == LogLevel.AUDIT) return true
        considered.increment()
        val now = clock.nowMillis()
        val accepted = synchronized(lock) {
            advanceWindow(now)
            windowOffered++
            refill(now)
            val allowedByRate = keepRate >= 1.0 || (keepRate > 0.0 && random.nextDouble() < keepRate)
            if (allowedByRate && tokens >= 1.0) {
                tokens -= 1.0
                true
            } else {
                false
            }
        }
        if (accepted) admitted.increment()
        return accepted
    }

    /** Current counters and the last measured offered rate; safe to call concurrently. */
    public fun stats(): SamplingStats = synchronized(lock) {
        SamplingStats(
            considered = considered.sum(),
            sampled = admitted.sum(),
            currentRate = keepRate,
            observedPerSecond = lastObservedPerSecond,
            buckets = 1,
        )
    }

    private fun advanceWindow(now: Long) {
        if (windowStartMillis == Long.MIN_VALUE) {
            windowStartMillis = now
            lastRefillMillis = now
            return
        }
        val elapsed = now - windowStartMillis
        if (elapsed < windowMillis) return
        lastObservedPerSecond = windowOffered * 1_000.0 / elapsed
        keepRate = if (lastObservedPerSecond <= targetEventsPerSecond) {
            1.0
        } else {
            (targetEventsPerSecond / lastObservedPerSecond).coerceIn(minRate, 1.0)
        }
        windowStartMillis = now
        windowOffered = 0
    }

    private fun refill(now: Long) {
        if (now <= lastRefillMillis) return
        tokens = min(burst.toDouble(), tokens + (now - lastRefillMillis) * targetEventsPerSecond / 1_000.0)
        lastRefillMillis = now
    }
}

/**
 * Requires every underlying sampler to accept an event; the first decline wins.
 *
 * Use it to combine independent policies, e.g. a per-action
 * [TokenBucketRateLimiter] with an [AdaptiveSampler] ceiling. Audit events
 * remain exempt as long as each member exempts them (both shipped limiters do).
 */
public class CompositeSampler(
    private val samplers: List<ContextualSampler>,
) : ContextualSampler {

    public constructor(vararg samplers: ContextualSampler) : this(samplers.toList())

    init {
        require(samplers.isNotEmpty()) { "CompositeSampler requires at least one sampler" }
    }

    override fun shouldSample(context: SamplingContext): Boolean {
        for (sampler in samplers) {
            if (!sampler.shouldSample(context)) return false
        }
        return true
    }
}
