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
output elsewhere — an Android Logcat bridge or an SLF4J adapter — without
re-implementing the sink. Writes are synchronized (single console lock).

### TextFileSink

JSON/text file with rotation and durability knobs:

```kotlin
TextFileSink(
    file = Path("audit/audit.jsonl"),
    codec = JsonEventCodec,                    // from ilogger-serialization
    append = true,                             // false truncates on start
    flushEveryWrite = true,                    // durability vs throughput
    rotation = TextFileSink.Rotation(maxBytesPerFile = 64 * 1024, maxFiles = 5),
)
```

| Param | Default | Notes |
|---|---|---|
| `file` | — | Parent directories are created automatically. |
| `codec` | `DefaultTextEventCodec` | Use `JsonEventCodec` for machine consumption. |
| `append` | `true` | `false` truncates an existing file at open. |
| `flushEveryWrite` | `true` | `false` buffers internally until `flush()` — higher throughput, worse crash durability. |
| `rotation` | `null` | Size-based ring: files roll to `<name>.1`, `<name>.2`, … newest first, deleting beyond `maxFiles`. `maxFiles = 0` deletes the file and keeps writing. |

Guarantees: survives process death when OS buffers are flushed
(`flushEveryWrite = true`); no network behavior; single-process writer
(two processes on one file is undefined); `IOException`s propagate to the
engine's error modes. Rotation triggers before the write that would exceed
`maxBytesPerFile`.

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
| `TextFileSink` (JSONL) | Survives restart (with flush) | Files to manage/rotate | Fast appends | Minutes | Filesystem-friendly hosts, simple ops |
| Room (`RoomLogTarget`/`RoomLogSink`) | Survives restart; queryable | DB schema + retention | Batched inserts | Quick — one line with `roomLogs` | Android/long-lived apps querying history |
| Remote collector (custom sink) | Your own buffering | Network budget, retries | Worst — network | Highest — full sink | Central ops/compliance requirements |

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
| Ship to a collector | implement write() with a batching client |

## The ui tail example (Android `:app`)

```kotlin
private val collectingSink = object : LogSink {
    override suspend fun write(event: LogEvent) {
        val line = DefaultTextEventCodec.encode(event)
        _eventLines.update { prev -> (listOf(line) + prev).take(50) }
    }
}
```

Compose collects `_eventLines` — the app shows live audit events **without**
any framework bridge; the sink owns the display concern.

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