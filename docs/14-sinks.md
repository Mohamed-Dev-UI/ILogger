# 14. Sinks

A sink receives finalized `LogEvent`s. Third-party systems are integrated by
implementing one interface:

```kotlin
public interface LogSink : AutoCloseable {
    public suspend fun write(event: LogEvent)
    // writeAll/flush/close provide efficient defaults
}
```

Writers of a sink promise: handle concurrent calls (the engine serializes
per-binding flow but not across bindings), be forgiving of failures (see
[16-dispatch-backpressure-error-modes.md](16-dispatch-backpressure-error-modes.md)).

## Built-in sinks (`ilogger-core`)

### ConsoleLogSink

One-line human output via the text codec; injectable `printlnFn` for
alternative consoles/streams.

### TextFileSink

JSON/text file with rotation and durability knobs:

```kotlin
TextFileSink(
    file = Path("audit/audit.jsonl"),
    codec = JsonEventCodec,                    // from ilogger-serialization
    append = true,
    flushEveryWrite = true,                    // durability vs throughput
    rotation = TextFileSink.Rotation(maxBytesPerFile = 64 * 1024, maxFiles = 5),
)
```

Rotation = size-based, ring of `maxFiles`; file lifetime is the app's.

## Writing a custom sink (verified pattern — `examples/custom-sink`)

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
| Ship to a collector | implement write() with batching client |

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

## Codec care

Text codecs decide event *encoding*, never data *protection*
([09-sensitive-data-protection.md](09-sensitive-data-protection.md)) —
masking/hashing already happened to the event before a sink sees it.
