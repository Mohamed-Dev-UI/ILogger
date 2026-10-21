# 16. Dispatch, backpressure & error modes

The engine owns the delivery pipeline so that **logging can degrade, never
fail your application**.

## Dispatch modes

| Mode | Meaning | Use for |
|---|---|---|
| `INLINE` | event construction + sink writes on the caller thread (awaited) | tests, must-see-now events, tiny apps, money-movement audits |
| `ASYNC` | single dedicated worker consumes a bounded channel | event-at-a-time production paths |
| `BUFFERED` | ASYNC + batching (`batchSize` events per sink flush) | **🏆 production default** — database/file sinks, high throughput |
| `DEFAULT` (`@Log(dispatch = ...)`) | follow the application configuration | annotation default — engine decides |

Selection guide:

| Criterion | `INLINE` | `ASYNC` | `BUFFERED` |
|---|---|---|---|
| Caller latency | Highest (sinks awaited inline) | One trySend-equivalent enqueue | Same as ASYNC |
| Ordering guarantees | Total, synchronous | Per queue | Per queue within batches |
| Sink efficiency | One write per event | One write per event | Real `writeAll` batches |
| Failure visibility | Immediate (may propagate per `ErrorMode`) | Reported-only | Reported-only |
| Crash window | None | Loss of last queued events | Loss of unflushed batch |
| Recommended when | Audits that must be durable before returning | Latency-sensitive low-rate paths | **Default for apps** |

`recordNow()` (the non-suspend path) requires an async-friendly dispatch:
generated code rejects `INLINE` on non-suspend functions at **compile time**,
and manual `recordNow(dispatch = INLINE)` throws
`IllegalStateException` at runtime.

## Backpressure: bounded by design

```kotlin
ILogger.create {
    dispatch = DispatchMode.BUFFERED
    bufferSize = 8_192        // events; finite — memory cannot grow unboundedly
    overflow = OverflowPolicy.DROP_OLDEST
    batchSize = 128
    flushIntervalMillis = 250
}
```

| OverflowPolicy | Effect | Loses what on overload |
|---|---|---|
| `DROP_OLDEST` | keeps the newest, ages the old out — **🏆 recommended** for observability | Oldest queued events |
| `DROP_NEWEST` (engine default) | producers win, latest arrival lost first | The event arriving now |
| `BLOCK` | suspension point at enqueue: producers slow to the consumer's speed | Nothing — at tail-latency cost to producers |
| `FAIL` | producer observes an exception when the buffer is full (explicit-failure contract) | Nothing lost silently — producers must handle the throw |

Overflow and closed-logger drops are *reported*, never silent: each one calls
`onDispatchError(event, sink, IllegalStateException("ilogger dispatch buffer full ..."))`.

`examples/advanced-audit`, scenario 1: a 30-event flood into `bufferSize = 8`
with a slow consumer delivers **exactly 8** — visible backpressure, no memory
growth.

## Error modes: what happens when a sink fails

| ErrorMode | Behavior |
|---|---|
| `DROP` | Report once (via `onDispatchError`) and move on. |
| `RETRY` | Retry with bounded exponential backoff, then report + drop. |
| `PROPAGATE` | Rethrow into the producer — **only meaningful for `INLINE`**; the async worker downgrades it to report+drop (there is no producer to fail). |

Defaults per level (built-in):

| Level | Default mode |
|---|---|
| `AUDIT`, `ERROR` | `RETRY` |
| `WARN`, `INFO`, `DEBUG`, `TRACE` | `DROP` |

```kotlin
ILogger.create {
    onErrorMode(LogLevel.WARN, ErrorMode.RETRY)
    retryMaxAttempts = 3              // per event write
    retryBaseBackoffMillis = 50       // exponential: 50ms, 100ms, 200ms ... (cap 10s per wait)
    onDispatchError = { event, sink, error -> /* last-resort telemetry */ }
}
```

Backoff formula: `retryBaseBackoffMillis * 2^attempt`, capped at 10 s per
wait; `retryMaxAttempts = 0` disables retries (first failure drops).

- Batched writes (`BUFFERED`) retry **per binding group**; a group is retried
  when at least one of its events has a non-`DROP` mode, and `PROPAGATE`
  degrades to `RETRY` inside the worker.
- None of these errors ever leak into caller semantics under async dispatch —
  the audit contract is "events may be lost under duress; the application
  never fails because of logging". `PROPAGATE` + `INLINE` is the narrow,
  explicit exception where audit delivery may block or fail an operation.

## Dispatch decisions in practice

| Platform | Engine default | Why |
|---|---|---|
| Tests / TestKit | `INLINE` | Deterministic verification (see [18](18-testing.md)) |
| Application default | `BUFFERED` + `DROP_OLDEST` | Batches pay off; recency wins under flood |
| Latency-critical single ops | per-event `@Log(dispatch = INLINE)` | Only where durability-before-return matters |
| Non-suspend callbacks | `recordNow` + engine default | `INLINE` is unavailable there |

Set the engine default **once**; per-event overrides remain the exception.

## flush() and close() semantics

- `flush()` is best-effort and bounded by `closeTimeoutMillis`: it enqueues a
  flush marker into the worker queue and waits (falling back to direct sink
  flushes when no worker is running). Producers racing the flush are not
  synchronized — treat it as "everything logged so far *should* be visible".
- `close()` is idempotent: closes the queue, lets the worker drain within
  `closeTimeoutMillis` (default 5 s), flushes and closes every sink. Events
  recorded after close are discarded and reported.
- Who creates the logger closes it — end of `main`, `Application.onTerminate`,
  or the DI teardown.

## Related knobs

- `workerDispatcher` — run the worker on a dedicated dispatcher
  ([26-configuration-reference.md](26-configuration-reference.md)).
- `minLevel` gates *diagnostics* only; `record()`/`recordNow()` (audit) are
  never silently filtered by it.
- Engine construction validates all of these at build time — see the
  invariants table in
  [26-configuration-reference.md](26-configuration-reference.md).
