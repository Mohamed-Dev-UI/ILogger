# 26. Configuration reference

Every runtime knob of the engine in one place: how to obtain a logger, every
`ILoggerBuilder` field with its default, what `build()` validates, the worker
lifecycle and ready-made profiles for development, testing and production.

## Ways to obtain a logger

There are three supported ways to get an `ILogger` (plus an explicit opt-out).
Pick one explicitly; they differ in who owns the lifecycle.

### Approach A — `ILogger.create { }` (recommended)

```kotlin
val logger = ILogger.create {
    sink(ConsoleLogSink())
    dispatch = DispatchMode.BUFFERED
}
// later, deterministically:
logger.close()
```

An independent instance. Your code owns construction and teardown, so the
lifecycle is explicit and the instance is injectable, swappable and
test-friendly. Every integration doc assumes this shape.

**🏆 Recommended.** The lifecycle is explicit, there are no hidden globals, and
tests can build a fresh instance per test.

### Approach B — `ILogger.configure { }` + `ILogger.default`

```kotlin
ILogger.configure { sink(ConsoleLogSink()) }   // installs the default
val noticed = ILogger.default                  // last configured instance
```

`configure` creates an instance **and** installs it as the process-wide
default. Calling `configure` again replaces the default; the replaced instance
is **not closed automatically**. When nothing was ever configured, `default`
lazily constructs a console logger with `DispatchMode.INLINE` (no background
threads).

Use this for tiny hosts (scripts, prototypes) or when a library must hand an
`ILogger` to the application's choice of logger.

| Criterion | A — `create` | B — `configure`/`default` |
|---|---|---|
| Simplicity | Explicit wiring, ~4 lines | ~1 line |
| Lifecycle ownership | Caller (deterministic) | Process-global, implicit |
| Multiple loggers (per-platform routing) | Yes | No — one default |
| Test isolation | Fresh instance per test | Global state leaks between tests |
| Replace/close hazards | None | Replaced default is never auto-closed |
| Development speed | Slower to wire | Fastest to start |
| Recommended when | Apps, libraries, anything audited | Scripts/prototypes; bridging libraries |

### Approach C — `ILogger.disabled`

```kotlin
val logger: ILogger = ILogger.disabled   // permanently off, never enabled
```

Drops every event (and shape: it is the same object the compile-time disable
flow builds on). Use it to satisfy a required parameter while deliberately
opting out — e.g. a flavor build with auditing disabled.

## `ILoggerBuilder` — complete reference

All fields are mutable `var`s with defaults; set what you need and leave the
rest. Precedence per event: **operation override → module configuration →
application configuration → library defaults**.

| Field | Type | Default | What it does |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | Master switch; `false` skips all dispatch (cheapest check, no allocation). |
| `minLevel` | `LogLevel` | `TRACE` | Floor for the *diagnostic* methods `trace..error`. See the caveat below — it does **not** gate `record()`/`recordNow()`. |
| `clock` | `LogClock` | `SystemClock` | `timestampMillis` source. Swap with `FixedClock(...)` in tests. |
| `idGenerator` | `EventIdGenerator` | `UuidEventIdGenerator` | Mints `LogEvent.id`. Swap with `SequentialIdGenerator()` (from `ilogger-testing`) in tests. |
| `actorProvider` | `ActorProvider` | `{ null }` | Last-resort actor resolution (after the explicit parameter and ambient `LogContext`). Must be thread-safe and cheap. |
| `dispatch` | `DispatchMode` | `BUFFERED` | Engine-wide mode for events that do not request `INLINE` per call. |
| `overflow` | `OverflowPolicy` | `DROP_NEWEST` | What happens when the bounded queue is full (see [16](16-dispatch-backpressure-error-modes.md)). |
| `bufferSize` | `Int` | `4096` | Capacity of the async pipeline, in **events**. Finite by design. |
| `batchSize` | `Int` | `128` | Events per batch under `BUFFERED` dispatch. |
| `flushIntervalMillis` | `Long` | `250` | Max age of a partial batch before the worker flushes it. |
| `workerDispatcher` | `CoroutineDispatcher?` | `null` | Dispatcher for the pipeline worker (see below). |
| `closeTimeoutMillis` | `Long` | `5_000` | Budget for `flush()` and `close()` draining. |
| `sampler` | `LogSampler?` | `null` | Diagnostic sampling (see [17](17-sampling.md)); audit events are never sampled unless configured explicitly at the application level. |
| `retryMaxAttempts` | `Int` | `3` | Retries per event write for `ErrorMode.RETRY` levels. |
| `retryBaseBackoffMillis` | `Long` | `50` | Base of the exponential retry backoff (see formula). |
| `onDispatchError` | `((LogEvent?, LogSink?, Throwable) -> Unit)?` | `null` | Observer for engine-level failures: overflowing logs, closed-logger drops, recursion drops, flush failures. |

Methods:

| Method | Effect |
|---|---|
| `sink(sink: LogSink, category: String? = null)` | Appends a binding. `category = null` binds as **catch-all** (receives every category plus its own). |
| `sampling(vararg rates: Pair<LogLevel, Double>)` | Replaces `sampler` with a `LevelSampler`; levels not listed run unsampled (rate 1.0). |
| `onErrorMode(level: LogLevel, mode: ErrorMode)` | Sets per-level sink-failure behavior (see defaults below). |

### Per-level failure behavior — defaults

`ErrorMode` decides what a sink exception means (values documented in
[16](16-dispatch-backpressure-error-modes.md)):

| Level | Default mode |
|---|---|
| `AUDIT` | `RETRY` |
| `ERROR` | `RETRY` |
| `WARN` | `DROP` |
| `INFO` | `DROP` |
| `DEBUG` | `DROP` |
| `TRACE` | `DROP` |

And for `RETRY` levels, attempts back off exponentially:

```
delay(attempt) = retryBaseBackoffMillis * 2^attempt   (per-wait cap: 10 s)
```

With the defaults (`maxAttempts = 3`, base 50 ms) a persistently failing sink
delays the pipeline ~50 + 100 + 200 ms before the event is reported and
dropped. Raise `retryBaseBackoffMillis` for network sinks; raise
`retryMaxAttempts` when transient failures must not lose audit events.

### Build-time validation

`build()` enforces documented invariants and fails fast with an
`IllegalStateException` (message given verbatim):

| Invariant | Message |
|---|---|
| `bufferSize > 0` | `bufferSize must be positive` |
| `batchSize in 1..bufferSize` | `batchSize must be within 1..bufferSize` |
| `flushIntervalMillis >= 0` | `flushIntervalMillis must be >= 0` |
| `closeTimeoutMillis >= 0` | `closeTimeoutMillis must be >= 0` |
| `retryMaxAttempts >= 0` | `retryMaxAttempts must be >= 0` |
| `retryBaseBackoffMillis >= 0` | `retryBaseBackoffMillis must be >= 0` |

All violations occur at `ILogger.create { }` time — a misconfigured engine
never starts half-valid.

### `minLevel` — what it does and does not gate

- `trace/debug/info/warn/error` are filtered by `minLevel` **and** `enabled`.
  Unseen calls cost a single boolean/ordinal check — no allocation.
- `record()` / `recordNow()` (the audit/event API used by generated code) are
  gated by **`enabled` only**. Audit traffic is never silently filtered by a
  level you tuned for diagnostics; to turn auditing off, set `enabled = false`
  or route its category consciously.

This asymmetry is deliberate: min level is a *diagnostics* knob, not an
accountability knob.

### `workerDispatcher`

The async pipeline runs on one worker coroutine. By default it gets a
single-threaded view of `Dispatchers.Default` named `ilogger-worker`.
`workerDispatcher` is only worth setting for two reasons:

1. **Android priority/scheduling** — run audit persistence on a dedicated
   dispatcher so it cannot compete with frame-time work.
2. **Test determinism** — hand in a test dispatcher instead of controlling
   real-time draining.

```kotlin
ILogger.create {
    sink(roomTarget)
    workerDispatcher = auditWorker   // Dispatchers.limitedParallelism(1, name = "audit-worker")
}
```

Do **not** use a multi-threaded dispatcher expecting parallel sink writes:
the engine is deliberately serial per binding so batching, ordering and error
policy stay understandable.

## Worker lifecycle

- The worker starts **lazily** on the first asynchronous event; a logger that
  only receives `INLINE` events never starts one.
- `flush()` is *best-effort* under `closeTimeoutMillis`: it enqueues a flush
  marker, waits for it, and falls back to flushing bindings directly.
  Ordering relative to producers is never guaranteed (enqueue never blocks).
- `close()` is idempotent: closes the queue, lets the worker drain within
  `closeTimeoutMillis`, cancels the worker scope, then closes every distinct
  sink (binding deduped). After close, events are discarded and the drop is
  reported through `onDispatchError`.

A good hygiene rule: whoever creates a logger closes it — typically at the end
of `main`, in `Application.onTerminate`, or in a DI-scoped `@AfterComponent`.

## Recommended profiles

Four tested starting points. Tune `bufferSize` to your worst-case event rate ×
sink latency, not to "big".

### Development — console, visible, honest about losses

```kotlin
ILogger.create {
    sink(ConsoleLogSink())
    dispatch = DispatchMode.INLINE          // see events synchronously while debugging
    onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
}
```

### Tests — `TestKit` (never wire production sinks)

```kotlin
val kit = TestKit.create()                  // INLINE + fixed clock + sequential ids
val dao = InMemoryTaskRepository().logged(kit.logger)
```
See [18-testing.md](18-testing.md). Determinism beats realism in tests.

### Production server — batched, durable, retried

```kotlin
ILogger.create {
    sink(ConsoleLogSink(), category = "console")          // optional echo
    sink(TextFileSink(Path.of("logs/audit.jsonl"), codec = JsonEventCodec))  // durable
    dispatch = DispatchMode.BUFFERED
    batchSize = 128
    bufferSize = 8_192
    onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
    retryBaseBackoffMillis = 100
    onDispatchError = { _, _, e -> e.printStackTrace() }
    closeTimeoutMillis = 10_000
}
```

### Android — small buffer, Room, retention, flush on lifecycle

```kotlin
val roomTarget = RoomLogTarget(retention = RoomLogRetention.keepDays(30))
ILogger.create {
    sink(roomTarget)                       // bind(roomTarget, db.logDao()) after build()
    dispatch = DispatchMode.BUFFERED
    bufferSize = 512                       // audits are low-rate; keep it small
    closeTimeoutMillis = 2_000
}
// onTrimMemory / Activity.onDestroy: logger.flush()
```
Full wiring in [21-android-app-guide.md](21-android-app-guide.md).

### Profiles compared

| Criterion | Dev (console/INLINE) | Test (TestKit) | Server (BUFFERED+file) | Android (BUFFERED+Room) |
|---|---|---|---|---|
| Simplicity | Lowest | Lowest | Medium | Medium |
| Runtime overhead | Highest per event (synchronous print) | None under audit volume | Low (batched) | Low (batched, small writes) |
| Durability | None | None | File + rotation | DB + retention |
| Development speed | Fastest | Fastest | Medium | Medium (needs schema) |
| Data you can lose | Everything (acceptably) | — | Last `bufferSize` on crash | Same, plus room for inline queue drain |
| Recommended when | Iterating on features | Every `@Log` suite | Long-lived JVM services | Mobile, offline-capable auditing |

Start from a profile; per-event overrides (`@Log(dispatch = ...)`) stay the
exception.
