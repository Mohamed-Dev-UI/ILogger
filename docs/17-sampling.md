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

Levels not listed are sampled at 1.0. The sampler is injectable
(`sampler: LogSampler?`) for deterministic or context-aware decisions.

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
