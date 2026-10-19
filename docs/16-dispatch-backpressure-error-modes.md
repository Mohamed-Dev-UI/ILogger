# 16. Dispatch, backpressure & error modes

The engine owns the delivery pipeline so that **logging can degrade, never
fail your application**.

## Dispatch modes

| Mode | Meaning | Use for |
|---|---|---|
| `INLINE` | event construction + sink writes on the caller thread | tests, must-see-now events, tiny apps |
| `ASYNC` | single dedicated worker consumes a bounded channel | production default |
| `BUFFERED` | ASYNC + batching (`batchSize` events per sink flush) | high-throughput apps |
| `DEFAULT` (`@Log(dispatch = ...)`) | follow the application configuration | annotation default |

`recordNow()` (the non-suspend path) requires an async-friendly dispatch —
inline dispatch on *non-suspend* callers is a compile-time rejection.

## Backpressure: bounded by design

```kotlin
ILogger.create {
    dispatch = DispatchMode.ASYNC
    bufferSize = 8_192        // events; finite — memory cannot grow unboundedly
    overflow = OverflowPolicy.DROP_OLDEST
    batchSize = 128
    flushIntervalMillis = 250
}
```

| OverflowPolicy | Effect |
|---|---|
| `DROP_OLDEST` | keeps the newest, ages the old out — default for observability |
| `DROP_NEWEST` | producers win, latest arrivals lost first |
| `BLOCK` | suspension point at enqueue: producers slow to the consumer's speed |
| `FAIL` | producer observes the exception (explicit-failure contract) |

`examples/advanced-audit`, scenario 1: a 30-event flood into `bufferSize = 8`
with a slow consumer delivers **exactly 8** — visible backpressure, no memory
growth.

## Error modes: what happens when a sink fails

Per level:

| Level (default) | Behavior |
|---|---|
| `AUDIT`, `ERROR` | `RETRY` — with backoff, `retryMaxAttempts` |
| everything else | `DROP` — diagnostic levels never block |

```kotlin
ILogger.create {
    onErrorMode(LogLevel.WARN, ErrorMode.RETRY)
    retryMaxAttempts = 3
    retryBaseBackoffMillis = 50
    onDispatchError = { event, sink, error -> /* last-resort telemetry */ }
}
```

- Failed batches are retried **per attempt**, not per batch, so one poisoned
  event can't stop the queue; `onDispatchError` sees final failures.
- Again: none of these errors ever leak into caller semantics — the audit
  contract is "events may be lost under duress; the application never fails
  because of logging".

## close() semantics

`close()` drains in-flight events within `closeTimeoutMillis` (default 5s),
then flushes and closes all sinks. Application shutdown should call it once;
[flush()](#) can be forced early (Android `onTrimMemory` in `:app` demo).
