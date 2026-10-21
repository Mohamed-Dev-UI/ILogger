# 17. Sampling

Sampling controls diagnostic event volume. **Audit events are never sampled**
— they are the product; sampling an audit event is a compile error in `@Log`
and an explicit override is required in code if you genuinely want it.

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

Levels not listed are sampled at 1.0.

## The sampler SPI

```kotlin
public fun interface LogSampler {
    public fun shouldSample(level: LogLevel, action: String): Boolean
}
```

The shipped implementation:

```kotlin
public class LevelSampler(
    private val rates: Map<LogLevel, Double>,
    private val random: Random = Random.Default,
) : LogSampler {
    public constructor(vararg rates: Pair<LogLevel, Double>, random: Random = Random.Default)
    // rates must be within [0.0, 1.0]; unlisted levels return true (never sampled)
}
```

Contract notes:

- `shouldSample` runs before event allocation — a declined event costs
  **zero allocations**.
- The `action` parameter currently receives the **synthetic level label**
  (`"log.trace"`, `"log.debug"`, …), not the event's real action — do not key
  per-action decisions off it; treat it as a level discriminator for now.
- Audit events always pass the sampler path at runtime as a safety net
  (annotation-level sampling of audit is already rejected at compile time).
- The per-event `@Log(sampling = …)` override bypasses the configured sampler
  and uses its own rate directly (`Random.nextDouble() < rate`).

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
  instead of sampling the audit level.
