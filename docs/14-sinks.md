# 14. Sinks

A sink receives finalized `LogEvent`s. Third-party systems are integrated by
implementing one interface:

```kotlin
public interface LogSink : AutoCloseable {
    public suspend fun write(event: LogEvent)
    // writeAll/flush/close provide efficient defaults
}
```

| Member | Contract |
|---|---|
| `write(event)` | One event. May throw — the configured `ErrorMode` decides what happens (see [16](16-dispatch-backpressure-error-modes.md)). |
| `writeAll(events)` | A batch. Default: sequential `write` calls; override to make one round-trip/transaction per batch. Under `DispatchMode.BUFFERED` the engine hands real batches. |
| `flush()` | Flush internal buffers; no-op default. Best-effort — called by `ILogger.flush()` and before `close()`. |
| `close()` | Release resources; no-op default. Called once by `ILogger.close()`. |

Writers of a sink promise: handle concurrent calls (the engine serializes
per-binding flow but applications may attach one sink to several loggers),
and never log via ILogger inside `write` (recursion is detected and dropped).

### Payload size guarantees

If a `limits` policy is configured, the engine bounds every event *before*
dispatch, so every sink — built-in or custom — receives payloads within the
same ceilings: strings no longer than `maxStringLength`, metadata/changes no
larger than `maxEntries`, actor attributes capped at `maxActorAttributes`,
failure summaries capped at `maxFailureFrames`, and an approximate byte
budget of `maxEventBytes`. Under `LimitAction.REJECT` oversized events never
reach any sink (reported via `onDrop` as `STRICT_REJECT`); under
`LimitAction.TRUNCATE` they arrive cut with the `…[truncated]` marker and the
`ilogger.truncated` metadata flag, counted in `DeliveryStats.truncated`.
Without `limits`, sinks that need their own ceiling (e.g. a JSONL writer
rotating files) enforce it themselves — see [24](24-limits-and-constraints.md).

### Tamper-evident chains

For audit scenarios where the log is evidence, wrap any sink in
`HashChainSink`: every forwarded event gains four `ilogger.trail.*` metadata
stamps linking it cryptographically to its predecessor (HMAC-SHA256 or
Ed25519), and `TrailVerifier` later proves whether the stored sequence was
modified, reordered, inserted into or deleted from. Off by default; keys,
rotation, verification modes and the threat model are covered in
[30](30-integrity.md).

### Encryption at rest

The file sink accepts an optional `cipher`; `AesGcmCipher` turns each stored
line into an authenticated `ilogenc1:<keyId>:…` token, so the file is opaque
without the key and any modification fails decryption instead of yielding
altered data. Rotation is handled by a key provider plus a keyring, and
compression, size rotation and fsync keep working unchanged. Keys, token
format, rotation and key-loss consequences are covered in
[31](31-encryption-at-rest.md).

## Triggers (sinks that cannot break the producer)

When you only need to observe events — persist them into your own table, mirror
them, alert — use a trigger instead of writing a sink. `TriggerSink` (and its
builder sugar) is public:

```kotlin
ILogger.create {
    onEvents { events ->                    // whole batch under BUFFERED dispatch
        myLogDao.insertAll(events.map(MyLogRow::from))
    }
    onEvent { event ->                      // or per event
        metrics.count(event.action)
    }
}
```

Full signatures:

```kotlin
public fun ILoggerBuilder.onEvent(
    category: String? = null,                              // routing, like sink()
    onError: ((LogEvent?, Throwable) -> Unit)? = null,     // null = drop silently
    block: suspend (LogEvent) -> Unit,
)

public fun ILoggerBuilder.onEvents(
    category: String? = null,
    onError: ((LogEvent?, Throwable) -> Unit)? = null,
    block: suspend (List<LogEvent>) -> Unit,               // never called empty
)
```

A trigger is a sink with a different failure contract: exceptions are reported
to its `onError` callback and never rethrown (a broken `onError` is contained
too), so the engine's retry/propagate policy does not apply and the
instrumented operation cannot fail because of it. Use a real sink when
delivery guarantees must influence the producer (for example
`ErrorMode.PROPAGATE` on synchronous audit writes). Room applications get the
battery-included version in [19-room-integration.md](19-room-integration.md).

**Recommended:** `onEvents` whenever the work can accept a batch (database
inserts, network flushes); `onEvent` for inherently per-event observers.

## Built-in sinks (`ilogger-core`)

### ConsoleLogSink

One-line human output via an injectable codec and writer:

```kotlin
ConsoleLogSink(codec = DefaultTextEventCodec)                  // via println
ConsoleLogSink(DefaultTextEventCodec, writeLine = ::println)   // explicit writer
```

The `writeLine: (String) -> Unit` indirection exists so host platforms route
output elsewhere — an SLF4J adapter, for example — without re-implementing the
sink. Android apps should use `LogcatSink` from `ilogger-android` instead:
`println` output is not visible in Logcat.

### LogcatSink (`ilogger-android`)

Android's native diagnostic destination:

```kotlin
import io.ilogger.android.LogcatSink

sink(LogcatSink(tag = "ILogger"), category = "diagnostics")
```

Maps TRACE/DEBUG/INFO/WARN/ERROR to the matching `android.util.Log` priority
(AUDIT → INFO), splits lines above 3,800 characters instead of losing them,
and supports per-event tags with `tagFor`. It is a `DirectLogSink`, so inline
dispatch is safe. See [28-android-module.md](28-android-module.md).

### TextFileSink

JSON/text file with rotation and durability knobs:

```kotlin
TextFileSink(
    file = Path("audit/audit.jsonl"),
    codec = JsonEventCodec,                    // from ilogger-serialization
    append = true,                             // false truncates on start
    flushEveryWrite = true,                    // OS durability
    rotation = TextFileSink.Rotation(
        maxBytesPerFile = 64 * 1024,
        maxFiles = 5,                          // archived files kept
        maxAgeMillis = 24 * 60 * 60 * 1000,    // optional lazy time rotation
        gzip = true,                           // archive as <name>.<n>.gz
        maxTotalBytes = 512L * 1024 * 1024,    // optional disk cap
    ),
    fsyncEveryWrite = false,                   // true = force to device
    ioDispatcher = Dispatchers.IO,             // optional override; default below
    clock = SystemClock,
)
```

| Param | Default | Notes |
|---|---|---|
| `file` | — | Parent directories are created automatically. |
| `codec` | `DefaultTextEventCodec` | Use `JsonEventCodec` for machine consumption. |
| `append` | `true` | `false` truncates an existing file at open. |
| `flushEveryWrite` | `true` | `false` buffers internally until `flush()` — higher throughput, worse crash durability. |
| `rotation` | `null` | Ring policy; see below. |
| `fsyncEveryWrite` | `false` | Forces every write to the storage device (`FileChannel.force`). Slow; use for audit data that must survive power loss. |
| `ioDispatcher` | `Dispatchers.IO.limitedParallelism(1, name = "ilogger-file-io")` | Suspend `write`/`flush` always hop to this dispatcher; a per-instance view that never runs two operations at once, so producers never block on disk and writes stay ordered. Override only to move file IO onto your own dispatcher. `writeDirect` always runs synchronously on the caller. |
| `clock` | `SystemClock` | Time source for `maxAgeMillis`. |

`TextFileSink.Rotation`:

| Param | Default | Notes |
|---|---|---|
| `maxBytesPerFile` | — | Rotation happens before the write that would exceed this size. A single larger event is still written once. |
| `maxFiles` | — | Archived files kept (`<name>.1` newest … `<name>.maxFiles` oldest). `0` keeps no archives and deletes the active file on each roll. |
| `maxAgeMillis` | `null` | Lazy time rotation: checked on the next write, not by a background timer. |
| `gzip` | `false` | Archives are gzip-compressed as `<name>.<n>.gz`; the active file is always plain text. |
| `maxTotalBytes` | `null` | Enforced after each write. Oldest archives are deleted first; the active file is never deleted, so a cap below its size keeps growing until the next roll. |

Durability levels: **BUFFERED** (`flushEveryWrite = false`) loses buffered
events on a crash; **OS** (`flushEveryWrite = true`) hands each event to the
OS page cache; **FSYNC** (`fsyncEveryWrite = true`) forces it to the storage
device. Guarantees: no network behavior; single-process writer (two processes
on one file is undefined); rotation is safe for every `maxFiles`, including
archives left by a previous process; `IOException`s propagate to the engine's
error modes. Time rotation is lazy: an idle sink rotates on its next event.

Suspend paths never block the producer on disk: `write` and `flush` hop to the
sink's IO dispatcher (per-instance, single-operation-at-a-time), so under
`ASYNC`/`BUFFERED` dispatch file IO is fully off the application's critical
path, and under `INLINE` a suspend `record` still suspends rather than
blocking the thread. Blocking callers opt in explicitly: `recordBlocking` and
`writeDirect` write on the calling thread.

### CompositeLogSink

Fans events out to several sinks inside *one* binding:

```kotlin
sink(CompositeLogSink(ConsoleLogSink(), TextFileSink(auditJsonl, JsonEventCodec)))
// or, explicitly:
sink(
    CompositeLogSink(
        ConsoleLogSink(),
        TextFileSink(auditJsonl, JsonEventCodec),
        errorMode = CompositeLogSink.ErrorMode.BEST_EFFORT,
        onError = { event, sink, error -> /* report */ },
    )
)
```

| Error mode | Behavior |
|---|---|
| `BEST_EFFORT` (default) | A failing sink never blocks the others and never propagates; failures go to `onError`. Retry policies upstream never duplicate writes to sinks that already succeeded. |
| `FAIL_FAST` | The first failure propagates immediately, skipping remaining sinks. Combined with upstream `RETRY` this **can duplicate events** in sinks that already received them — keep event-id deduplication in mind. |

**Composite vs. separate bindings compared**

| Criterion | `CompositeLogSink` | Multiple `sink(...)` bindings |
|---|---|---|
| Routing | All events to all inner sinks | Full category routing per binding |
| Error semantics | One shared `ErrorMode` for the group | Per-level engine modes apply per binding |
| Ordering across sinks | Same event delivered inline in one pass | Binding order, potentially far apart in time |
| Simplicity | One line for "console + file" | Two bindings — clearer at scale |
| Recommended | Single audience needing several destinations | **🏆 Default choice** — most setups |

**Recommendation:** separate bindings (routing, error modes, batching differ
per audience); reach for `CompositeLogSink` when you specifically need "these
sinks always move together as one destination".

## Durability: where should events go?

| Destination | Durability | Production cost | Speed | Dev speed | Recommended when |
|---|---|---|---|---|---|
| Console only | None | Zero infra | Immediate, printed | Instant | Development shells |
| Logcat (`LogcatSink`, `ilogger-android`) | None; visible while attached | Zero infra | Immediate | Instant | Android diagnostics and development |
| `TextFileSink` (JSONL) | Survives restart (with flush) | Files to manage/rotate | Fast appends | Minutes | Filesystem-friendly hosts, simple ops |
| Room (`RoomLogTarget`/`RoomLogSink`) | Survives restart; queryable | DB schema + retention | Batched inserts | Quick — one line with `roomLogs` | Android/long-lived apps querying history |
| Remote collector (`RemoteLogSink`, `ilogger-remote`) | Durable spool across restarts | Network budget, retries, collector | Worst — network | One sink + transport | Central ops/compliance requirements |

### Remote shipping

`ilogger-remote` ships batches over HTTP with retries, a durable spool and a
dead-letter fallback, so a collector outage does not lose events or block
producers:

```kotlin
ILogger.create {
    sink(
        RemoteLogSink(
            RemoteLogSinkConfig(
                transport = JavaHttpTransport("https://logs.example.com/v1/events"),
                spool = FileRemoteSpool(Path.of("var/log/ilogger-spool.jsonl")),
            ),
        ),
    )
    dispatch = DispatchMode.BUFFERED
}
```

Full configuration, transport adapters (JDK HTTP, OkHttp, Ktor), TLS pinning
and delivery semantics: [33-remote-sink.md](33-remote-sink.md).

### Bridging an existing logging stack

ILogger can feed SLF4J/Logback or `java.util.logging`, and consume their
records as structured events:

```kotlin
ILogger.create {
    sink(Slf4jLogSink())          // audit events → existing SLF4J pipeline
    dispatch = DispatchMode.BUFFERED
}

val appender = ILoggerAppender(logger).apply { context = logbackContext; start() }
logbackContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
```

`ilogger-jul` provides the equivalent `JulLogSink`/`JulLogHandler`. Level
mapping, MDC/key-value context, exception preservation and loop protection
are covered in [34-logging-bridges.md](34-logging-bridges.md).

### OpenTelemetry traces and metrics

`ilogger-otel` connects the pipeline to an OTel SDK without making core depend
on it (the OTel API is `compileOnly`):

```kotlin
ILogger.create {
    enricher(OtelTraceEnricher())                       // traceId/spanId on every event
    sink(OtelSinkMetrics.wrap(ConsoleLogSink()))        // writes/errors/duration
}

val metrics = OtelLoggerMetrics(logger, meterProvider.get("app"))
// optional: sink(OtelSpanLogSink()) exports events as instantaneous spans
```

Trace ids are captured at admission, where the caller's span is still current;
sinks can export either the correlated events or one span per event. Queue
depth, drops by reason, truncation and per-sink latency are exposed as
observable instruments, so no background thread polls the engine. Install,
propagation and cardinality guidance: [35-opentelemetry.md](35-opentelemetry.md).

## Writing a custom sink (verified — `examples/custom-sink`)

```kotlin
class AlertSink(private val react: (LogEvent) -> Unit) : LogSink {
    override suspend fun write(event: LogEvent) {
        when (event.level) {
            LogLevel.WARN, LogLevel.ERROR, LogLevel.AUDIT -> react(event)
            else -> Unit
        }
    }
}
```

Compositional patterns:

| Pattern | How |
|---|---|
| Alert on high-signal | custom sink; compare `level`/`outcome` |
| UI tail (SDK) | bridge into `MutableStateFlow`, capped list |
| Durable audit file | `TextFileSink` + `JsonEventCodec` + category binding |
| Persist in-app DB without wiring a schema | `onEvents` trigger or `RoomLogSink` |
| Ship to a collector | `RemoteLogSink` + a `RemoteTransport` (see [33](33-remote-sink.md)) |

## The UI tail example (Android `:app`)

The reference app keeps the structured event, not just a formatted line, so
the UI can filter by level/outcome/category and inspect the typed payload.
Both lists are capped, and a paused flag freezes the view without dropping
events from the other sinks:

```kotlin
private val _events = MutableStateFlow<List<LogEvent>>(emptyList())

val sink = object : LogSink {
    override suspend fun write(event: LogEvent) = accept(listOf(event))
    override suspend fun writeAll(events: List<LogEvent>) = accept(events)
}

private fun accept(batch: List<LogEvent>) {
    if (paused) return
    _events.update { prev -> (batch.asReversed() + prev).take(400) }
}
```

Compose collects `_events` — the app shows live audit events **without** any
framework bridge; the sink owns the display concern. Batch support matters:
`BUFFERED` dispatch calls `writeAll`, so one UI update can cover many events.

## Codecs (`EventCodec`)

Text sinks encode through one SPI:

```kotlin
public fun interface EventCodec {
    public fun encode(event: LogEvent): String
}
```

Implementations must be side-effect free, fast and safe for concurrent calls.
`DefaultTextEventCodec` (the console human format) renders: timestamp, level,
`schemaId`, non-`SUCCESS` outcomes, `actor=`, `Entity(id)`, `via source`,
`corr=`, `cause=`, metadata map, changes, and a `failure{...}` tail — a
deliberately non-structured format; machine consumption should use
`JsonEventCodec` ([15-json-codec.md](15-json-codec.md)).

```kotlin
// custom codec example — CSV events for a spreadsheet-pipeline consumer
object CsvEventCodec : EventCodec {
    override fun encode(event: LogEvent): String =
        listOf(event.timestampMillis, event.level.name, event.schemaId,
               event.outcome.name, event.entityId).joinToString(",")
}
```

## Codec care

Text codecs decide event *encoding*, never data *protection*
([09-sensitive-data-protection.md](09-sensitive-data-protection.md)) —
masking/hashing already happened to the event before a sink sees it.