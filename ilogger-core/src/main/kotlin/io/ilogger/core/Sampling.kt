package io.ilogger.core

import io.ilogger.annotations.LogLevel
import kotlin.random.Random

/**
 * Everything a sampler may consider when deciding whether an event is emitted.
 *
 * Instances are created by the engine after context resolution and immediately
 * before event allocation, so a declined event costs no [LogEvent] allocation.
 *
 * @param operationKey non-null when the event belongs to a correlated
 *   operation (normally the ambient correlation id). The engine caches the
 *   decision per operation key + action so START/SUCCESS/FAILURE of one action
 *   are sampled consistently.
 * @param actor resolved actor of the event, when available. [RateLimitScope.ACTOR]
 *   uses it to key independent token buckets.
 */
public class SamplingContext(
    public val level: LogLevel,
    public val action: String,
    public val category: String? = null,
    public val outcome: Outcome = Outcome.SUCCESS,
    public val metadata: Map<String, Any?> = emptyMap(),
    public val operationKey: String? = null,
    public val actor: Actor? = null,
)

/**
 * Rich sampler SPI, preferred for new code. The legacy
 * [LogSampler] remains supported: assign it to `sampler` and the engine adapts
 * it through [asContextualSampler].
 */
public fun interface ContextualSampler {
    public fun shouldSample(context: SamplingContext): Boolean
}

/** Adapts the two-argument [LogSampler] SPI to [SamplingContext]. */
public fun LogSampler.asContextualSampler(): ContextualSampler =
    ContextualSampler { context -> shouldSample(context.level, context.action) }

/** Per-action sampling; actions not listed are never sampled (rate 1.0). */
public class ActionSampler(
    private val rates: Map<String, Double>,
    private val random: Random = Random.Default,
) : ContextualSampler {

    init {
        rates.values.forEach { rate ->
            require(rate in 0.0..1.0) { "Sampling rate must be within [0..1], got $rate" }
        }
    }

    override fun shouldSample(context: SamplingContext): Boolean {
        val rate = rates[context.action] ?: return true
        return random.nextDouble() < rate
    }
}

/**
 * Per-category sampling; categories not listed use [defaultRate] (1.0 by
 * default). Events without a category use [defaultRate] as well.
 */
public class CategorySampler(
    private val rates: Map<String, Double>,
    private val defaultRate: Double = 1.0,
    private val random: Random = Random.Default,
) : ContextualSampler {

    init {
        require(defaultRate in 0.0..1.0) { "Sampling rate must be within [0..1], got $defaultRate" }
        rates.values.forEach { rate ->
            require(rate in 0.0..1.0) { "Sampling rate must be within [0..1], got $rate" }
        }
    }

    override fun shouldSample(context: SamplingContext): Boolean {
        val rate = context.category?.let { rates[it] } ?: defaultRate
        return random.nextDouble() < rate
    }
}

/**
 * Uniform rate sampler: emits a fraction of events regardless of action or
 * category. Intended for simple volume control and deterministic tests via the
 * injected [Random]. For time-based admission control (hard per-key ceilings,
 * bursts) use [TokenBucketRateLimiter] instead.
 */
public class RateLimitSampler(
    private val rate: Double,
    private val random: Random = Random.Default,
) : ContextualSampler {

    init {
        require(rate in 0.0..1.0) { "Sampling rate must be within [0..1], got $rate" }
    }

    override fun shouldSample(context: SamplingContext): Boolean = random.nextDouble() < rate
}
