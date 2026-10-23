# 16. Dispatch, backpressure & error modes

The engine owns the delivery pipeline so that **logging can degrade, never
fail your application**.

## Dispatch modes

| Mode | Meaning | Use for |
|---|---|---|
| `INLINE` | event construction + sink writes on the caller thread (awaited) | tests, must-see-now events, tiny apps, money-movement audits |
| `ASYNC` | one bounded pipeline + worker per sink binding | event-at-a-time production paths |
| `BUFFERED` | ASYNC + batching (`batchSize` events per sink flush) | **🏆 production default** — database/file sinks, high throughput |
| `DEFAULT` (`@Log(dispatch = ...)`) | follow the application configuration | annotation default — engine decides |

Every binding owns its own bounded queue and worker(s), so a slow or failing
sink never delays the other sinks. `sinkConcurrency` (default `1`) controls how
many workers one binding gets; values above 1 relax per-sink ordering.

Selection guide:

| Criterion | `INLINE` | `ASYNC` | `BUFFERED` |
|---|---|---|---|
| Caller latency | Highest (sinks awaited inline) | One bounded enqueue | Same as ASYNC |
| Ordering guarantees | Total, synchronous | Per binding | Per binding within batches |
| Sink efficiency | One write per event | One write per event | Real `writeAll` batches |
| Failure visibility | Immediate (may propagate per `ErrorMode`) | Reported-only | Reported-only |
| Crash window | None | Loss of last queued events | Loss of unflushed batch |
| Recommended when | Audits that must be durable before returning | Latency-sensitive low-rate paths | **Default for apps** |

`recordNow()` (the non-suspend path) supports every mode, including `INLINE`:
inline delivery is bounded by `blockTimeoutMillis` so a stalled sink cannot
block a synchronous caller forever. Prefer `recordBlocking(...)`, which takes
an explicit `timeoutMillis` and returns whether the event was delivered or
admitted.

## Backpressure: bounded by design

```kotlin
ILogger.create {
    dispatch = DispatchMode.BUFFERED
    bufferSize = 8_192        // events per binding; finite — memory cannot grow unboundedly
    overflow = OverflowPolicy.DROP_OLDEST
    batchSize = 128
    flushIntervalMillis = 250
    blockTimeoutMillis = 5_000 // upper bound for BLOCK admission
}
```

| OverflowPolicy | Effect | Loses what on overload |
|---|---|---|
| `DROP_OLDEST` | keeps the newest, ages the old out — **🏆 recommended** for observability | Oldest queued events |
| `DROP_NEWEST` (engine default) | producers win, latest arrival lost first | The event arriving now |
| `BLOCK` | waits (suspend or bounded monitor wait) for capacity, up to `blockTimeoutMillis` | Nothing while capacity frees in time; otherwise the event |
| `FAIL` | producer observes an exception when the buffer is full (explicit-failure contract) | Nothing lost silently — producers must handle the throw |

Diagnostics (`trace..error`) never block on a saturated queue: they honor
`DROP_OLDEST` or, by default, reject the newest event.

### Producer helpers: shed before the engine drops

High-volume producers can inspect capacity and admit events opportunistically:

```kotlin
if (logger.isLoggable(LogLevel.DEBUG, "cache.lookup") && logger.availableCapacity() > 1_000) {
    logger.tryRecord("cache.lookup", level = LogLevel.DEBUG, entityId = key)
}
```

| Helper | Contract |
|---|---|
| `hasSinks()` | enabled, open and at least one binding — cheap pre-check |
| `isBacklogged()` | no matched pipeline has queue capacity |
| `availableCapacity()` | free slots on the **most constrained** binding (`0` = next async event hits the policy) |
| `tryRecord(...)` | never blocks, never throws; `false` on any drop |
| `recordOrDrop(...)` | `Unit`-returning sugar over `tryRecord` for fire-and-forget call sites |

`tryRecord` deliberately bypasses `BLOCK` and `FAIL` — a full queue means the
event is dropped with `OVERFLOW_NEWEST` instead of waiting or throwing. Under
`INLINE` it only writes sinks implementing `DirectLogSink` and returns `false`
otherwise, so an opportunistic call can never block on I/O. All drops are
reported and counted like any other admission.

## Loss accounting and dead letters

Every lost delivery is classified and counted exactly once. Observe losses
synchronously (the callback runs on the losing thread and must be cheap):

```kotlin
ILogger.create {
    sink(ConsoleLogSink())
    onDrop = { dropped -> metrics.count(dropped.reason) }
    deadLetter(TextFileSink(Path.of("logs/dead-letter.jsonl"))) // background pipeline
}
```

`DropReason` values: `OVERFLOW_OLDEST`, `OVERFLOW_NEWEST`, `CLOSED`,
`RETRY_EXHAUSTED`, `SINK_FAILURE`, `SAMPLED`, `SUPPRESSED`, `STRICT_REJECT`,
`MISSING_BINDING`. `DroppedEvent.event` is `null` for pre-allocation drops
(sampling, suppression, and late events after close).

`stats()` returns point-in-time counters: `accepted`, `delivered`, `pending`
(queued), `inFlight` (currently inside a sink), `droppedByReason`, and
`bindings` — a `BindingStats` entry per sink with its own `index`, `category`,
`sink`, `pending`, `inFlight`, `accepted` and `delivered`. Per-binding counts
describe pipeline traffic; direct inline diagnostics appear only in the logger
totals. `onDispatchError` remains the observer for engine-level errors such as
sink exceptions and dead-letter queue overflow.

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

- Batched writes (`BUFFERED`) retry **per binding pipeline**; a group is
  retried when at least one of its events has a non-`DROP` mode, and
  `PROPAGATE` degrades to `RETRY` inside the worker. Retries in one binding do
  not stall any other binding.
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
| Non-suspend callbacks | `recordBlocking` (or `recordNow`) | No suspension available; inline delivery is timeout-bounded |

Set the engine default **once**; per-event overrides remain the exception.

## flush(), awaitIdle() and close() semantics

- `flush()` is a **watermark barrier**: it captures how many events were
  admitted to each binding, waits until all of them are delivered or dropped,
  then flushes every distinct sink. `flush(timeoutMillis)` returns a
  `FlushReport(timedOut, accepted, delivered, pending, inFlight,
  droppedByReason)` even when the deadline expires.
- `awaitIdle(timeoutMillis)` waits for the same watermark without flushing
  sinks — useful to assert that a pipeline drained.
- `close()` is idempotent: it closes every binding queue, drains within
  `closeTimeoutMillis` (default 5 s), flushes and closes every distinct sink
  and the dead-letter sink. Events recorded after close are discarded and
  reported as `CLOSED`.
- `closeAndReport()` returns a `CloseReport` with the final counters plus
  `sinkFailures` (close/flush exceptions are collected, not thrown).
- Who creates the logger closes it — end of `main`, `Application.onTerminate`,
  or the DI teardown.

## Related knobs

- `sinkConcurrency` — workers per binding (default `1`, ordering preserved).
- `blockTimeoutMillis` — upper bound for `BLOCK` admission and inline
  delivery from non-suspend callers.
- `onDrop` / `deadLetter(...)` — per-event loss observation and a background
  dead-letter destination.
- `workerDispatcher` — run pipeline workers on a dedicated dispatcher
  ([26-configuration-reference.md](26-configuration-reference.md)).
- `minLevel` gates *diagnostics* only; `record()`/`recordNow()` (audit) are
  never silently filtered by it.
- Engine construction validates all of these at build time — see the
  invariants table in
  [26-configuration-reference.md](26-configuration-reference.md).
- `CompositeLogSink` forwards engine batches as one `writeAll` per inner sink;
  only use `DirectLogSink` implementations if you rely on the INLINE
  diagnostic fast path.
