# 17. Sampling

Sampling controls diagnostic event volume. **Audit events are never sampled
by default** — they are the product; sampling an audit event is a compile
error in `@Log` and requires the explicit `auditSampling = true` switch in
code if you genuinely want it.

## Engine-level configuration

```kotlin
ILogger.create {
    sampling(
        LogLevel.TRACE to 0.1,   // 10% of traces
        LogLevel.DEBUG to 0.5,   // half of debugs
        LogLevel.INFO to 1.0,    // everything (default anyway)
    )
}
```

Levels not listed are sampled at 1.0. Listing `AUDIT` turns on
`auditSampling` automatically — an explicit audit rate is the deliberate act
the default protects against.

## The sampler SPI

New code should implement `ContextualSampler`, which receives the event's real
identity and payload:

```kotlin
public class SamplingContext(
    public val level: LogLevel,
    public val action: String,
    public val category: String? = null,
    public val outcome: Outcome = Outcome.SUCCESS,
    public val metadata: Map<String, Any?> = emptyMap(),
    public val operationKey: String? = null,
)

public fun interface ContextualSampler {
    public fun shouldSample(context: SamplingContext): Boolean
}
```

```kotlin
ILogger.create {
    sampling(
        CategorySampler(mapOf("http" to 0.05), defaultRate = 1.0),
    )
}
```

Shipped implementations:

| Sampler | Decision input | Unlisted values |
|---|---|---|
| `LevelSampler` | level | rate 1.0 |
| `ActionSampler` | action | rate 1.0 |
| `CategorySampler` | category | `defaultRate` (1.0) |
| `RateLimitSampler` | none — uniform rate | — |
| `TokenBucketRateLimiter` | time-based, per level/action/category/actor | every key has its own bucket |
| `AdaptiveSampler` | offered volume vs. a target rate | sheds diagnostics only |
| `CompositeSampler` | every delegate must accept | — |

All rate-based and probabilistic samplers accept an injected `kotlin.random.Random` for
deterministic tests; the limiters accept an injected `LogClock`.

The legacy two-argument SPI keeps working:

```kotlin
public fun interface LogSampler {
    public fun shouldSample(level: LogLevel, action: String): Boolean
}
```

Assign it to `sampler`; the engine adapts it through `asContextualSampler()`.
The `action` parameter now receives the **real action** (it used to receive a
synthetic `"log.debug"` label).

Contract notes:

- `shouldSample` runs before event allocation — a declined event costs
  **zero allocations**.
- Correlated operations decide once: when an ambient correlation id is
  present, the decision is cached per `correlationId + action`, so START,
  SUCCESS and FAILURE events of one action are consistently sampled. The
  cache is bounded (1024 entries, least-recently-used eviction).
- The per-event `@Log(sampling = …)` override bypasses the configured sampler
  and uses its own rate directly (`Random.nextDouble() < rate`). Audit events
  remain exempt from per-event overrides.
- `ILogger.isLoggable(level, action, category)` performs the same admission
  checks (master switch, min level, sampler) without building an event, for
  producers whose payload is expensive to construct. For rate limiters and the
  adaptive sampler this is a real admission attempt: it consumes a token or a
  probability draw, and an uncorrelated `record` immediately afterwards may be
  declined if capacity ran out in between. Correlated operations reuse the
  cached decision and agree.
- Without `auditSampling = true`, the sampler is not consulted for
  `LogLevel.AUDIT` and the event is always emitted.

## Rate limiting (token buckets)

`TokenBucketRateLimiter` bounds admission in time rather than by probability.
Each key owns a bucket that holds up to `burst` tokens and refills at
`permitsPerSecond`:

```kotlin
ILogger.create {
    sampling(
        TokenBucketRateLimiter(
            permitsPerSecond = 100.0,
            burst = 200,
            scope = RateLimitScope.ACTION, // GLOBAL | LEVEL | ACTION | CATEGORY | ACTOR
        ),
    )
}
```

| Scope | Bucket key |
|---|---|
| `GLOBAL` | one bucket for every event |
| `LEVEL` | the event level |
| `ACTION` | the action code (default) |
| `CATEGORY` | the category; uncategorized events share one bucket |
| `ACTOR` | actor type + id; events without an actor share one bucket |

Buckets start full and are created on demand. The map is bounded by
`maxBuckets` (default 1 024, least-recently-used eviction), so high-cardinality
scopes such as actions or actors cannot grow memory without limit. A key whose
bucket was evicted simply starts again with a full burst.

**Audit events are never limited**: `TokenBucketRateLimiter` and
`AdaptiveSampler` return `true` for `LogLevel.AUDIT` without touching a bucket
or a counter. This holds even when `auditSampling = true` and the engine
consults the sampler for audit events.

`stats()` exposes `considered`, `sampled` (admitted), `skipped`, the configured
rate and the live bucket count:

```kotlin
val limiter = TokenBucketRateLimiter(permitsPerSecond = 50.0, scope = RateLimitScope.ACTOR)
ILogger.create { sampling(limiter) }
// ...
val stats = limiter.stats()
println("shed ${stats.skipped} of ${stats.considered} events; ${stats.buckets} active keys")
```

The engine's `DeliveryStats.droppedByReason[DropReason.SAMPLED]` counts the
same losses at logger level (it also includes probabilistic sampling and
per-operation cached decisions).

## Adaptive sampling

`AdaptiveSampler` keeps everything at normal volume and sheds proportionally
under sustained overload:

```kotlin
ILogger.create {
    sampling(
        AdaptiveSampler(
            targetEventsPerSecond = 2_000.0,
            windowMillis = 1_000,
            minRate = 0.01, // never silence diagnostics completely
        ),
    )
}
```

- Once per `windowMillis` the sampler measures the offered diagnostic volume
  (every event it is asked about, whether admitted or shed) and sets the
  admission probability to `target / observed`, clamped to `[minRate, 1.0]`.
- A token bucket refilled at `targetEventsPerSecond` caps admissions at all
  times, including the first window — a 100 000-events/s flood from a cold
  start is bounded by the burst plus one window of refill, not by the first
  measurement.
- When the volume drops below the target, the next window restores full
  admission.
- Audit events bypass the sampler, are not counted as offered volume, and do
  not consume tokens, so an audit stream is unaffected by a diagnostic flood.

Inject a seeded `Random` and a `FixedClock` to make tests deterministic;
`stats()` returns the current admission probability (`currentRate`), the last
measured volume (`observedPerSecond`), `considered`, `sampled` and `skipped`.

## Combining samplers

`CompositeSampler` requires every delegate to accept an event; the first
decline wins. Combine independent policies, for example a hard per-actor
ceiling with an adaptive global ceiling:

```kotlin
ILogger.create {
    sampling(
        CompositeSampler(
            TokenBucketRateLimiter(permitsPerSecond = 20.0, burst = 40, scope = RateLimitScope.ACTOR),
            AdaptiveSampler(targetEventsPerSecond = 5_000.0),
        ),
    )
}
```

Order matters for accounting (each delegate sees every event that the previous
one accepted), not for the final decision.

## A custom sampler

```kotlin
// example: sample trace only in hot paths marked by category
class CategoryTraceSampler : LogSampler {
    override fun shouldSample(level: LogLevel, action: String): Boolean =
        level != LogLevel.TRACE || threadLocalHotPath.get() != true
}
ILogger.create { sampler = CategoryTraceSampler() }
```

Options compared:

| Option | Config surface | Behavior | Verdict |
|---|---|---|---|
| `sampling(TRACE to 0.1, DEBUG to 0.5)` | One builder call | Per-level rates | **🏆 Recommended** — covers 95 % of needs |
| Custom `LogSampler` | One class | Any deterministic/context rule | Only for rate-by-content policies |
| Per-event `@Log(sampling = …)` | Per annotation | Local to one noisy event | One particularly chatty side-event |

## Per-event override in `@Log`

```kotlin
@Log(action = "cache.hit", level = LogLevel.DEBUG, sampling = 0.05)
suspend fun cacheHit(key: String)
```

`-1.0` (the default) means "not set" — engine configuration applies.

## Deterministic suppression in tests

Rate 0.0 is deterministic — a documented tool to silence a level in tests:

```kotlin
ILogger.create {
    sampling(LogLevel.TRACE to 0.0)
    ...
}
```

`examples/advanced-audit`, scenario 3: three trace events at rate 0.0 are
suppressed while a debug event passes — the assertion-ready behavior.

## Pitfalls

- Sampling applies **after** the min-level gate (both can be combined; the
  min-level floor is usually enough in tests, sampling is for noisy
  diagnostics in production such as per-item cache hits).
- `onDispatchError` is **not** sampled: telemetry of engine failures cannot
  be self-suppressed.
- For audit-volume control use the audit category routing + bounded buffers
  instead of sampling the audit level. Rate limiters deliberately do not apply
  to audit events even when `auditSampling = true`.
- A token bucket measures *time*, so an injected `FixedClock` that never moves
  never refills it. Advance the clock in tests.
- With correlated operations the engine caches the decision and does not
  consult the sampler again; sampler counters therefore measure decisions, not
  total logged events. `DeliveryStats.droppedByReason[DropReason.SAMPLED]` is
  the authoritative engine-level count.
