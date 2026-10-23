# 34. Logging-stack bridges (`ilogger-jul`, `ilogger-slf4j`)

Two optional artifacts connect ILogger to the logging stacks a JVM
application already has:

| Artifact | Direction | Dependencies |
|---|---|---|
| `ilogger-jul` | JUL → ILogger (`JulLogHandler`), ILogger → JUL (`JulLogSink`) | none beyond `ilogger-core` |
| `ilogger-slf4j` | SLF4J/Logback → ILogger (`ILoggerAppender`), ILogger → SLF4J (`Slf4jLogSink`) | `compileOnly` slf4j-api and logback-classic |

Neither artifact adds its host libraries to your classpath. `ilogger-jul`
needs nothing; `ilogger-slf4j` compiles against SLF4J and Logback, and you
supply them because any app using those bridges already does. The Logback
appender class is only loaded when you reference it, so an SLF4J-only setup
never touches Logback.

## Choosing a direction

| Situation | Bridge |
|---|---|
| Legacy library logs through JUL; you want them in the audit trail | `JulLogHandler` |
| Framework logs through SLF4J (Spring, Ktor, Hibernate); audit trail should include them | `ILoggerAppender` |
| ILogger events should appear in existing JUL handlers/`logging.properties` | `JulLogSink` |
| ILogger events should appear in the existing Logback/Log4j 2 pipeline | `Slf4jLogSink` |
| Both stacks should see the same events | install both; read the loop-protection section first |

## JUL inbound — `JulLogHandler`

```kotlin
val handler = JulLogHandler(logger)                       // action "jul.<logger name>"
val legacy = Logger.getLogger("com.example.legacy")
legacy.addHandler(handler)
legacy.level = Level.ALL                                  // JUL filters before the handler

// application shutdown
legacy.removeHandler(handler)
```

Each `LogRecord` becomes one event:

| LogRecord | Event field |
|---|---|
| `loggerName` | `source`; appended to `actionPrefix` for `action` |
| `level` | `LogLevel` (see mapping) |
| `message` + `parameters` | `metadata["message"]`, `metadata["param.N"]` |
| `thrown` | `failure` — full stack summary, exception type and message |

## JUL outbound — `JulLogSink`

```kotlin
ILogger.create {
    sink(JulLogSink())                    // logger "io.ilogger.bridge.events"
    dispatch = DispatchMode.BUFFERED
}
```

The sink publishes a `LogRecord` with the event timestamp, the event's
`source` as `sourceClassName`, and the fixed text codec's line as the
message. Configure handlers/formatters on the `io.ilogger.bridge.events`
logger as usual.

## SLF4J inbound — `ILoggerAppender`

The appender is a Logback appender. Programmatic setup (the usual choice when
the destination is an application-owned `ILogger`):

```kotlin
val context = LoggerFactory.getILoggerFactory() as LoggerContext
val appender = ILoggerAppender(logger).apply {
    this.context = context
    start()
}
context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
```

Every record becomes one event:

| Logging event | Event field |
|---|---|
| logger name | `source`; appended to `actionPrefix` for `action` |
| level | `LogLevel` (see mapping) |
| formatted message | `metadata["message"]` |
| MDC entries | `metadata["mdc.<key>"]` (disable with `includeMdc = false`) |
| SLF4J 2 key/values | `metadata["kv.<key>"]` (disable with `includeKeyValues = false`) |
| throwable proxy | `failure` — type, message, root cause and stack summary |

The appender forwards through `recordBlocking` with `DispatchMode.ASYNC` by
default: logging frameworks are synchronous, so the engine's queue is what
keeps a slow sink off application threads. Raise `timeoutMillis` only if your
pipeline uses a `BLOCK` overflow policy and you accept that cost in the
logging call.

A Logback.xml-only setup cannot construct an appender that needs an
`ILogger` instance. Keep the appender wiring in a small configuration class
(or a custom `Appender` subclass with a static logger holder) and add it from
there; the appender itself has no XML configuration needs.

## SLF4J outbound — `Slf4jLogSink`

```kotlin
ILogger.create {
    sink(Slf4jLogSink())                  // logger "io.ilogger.bridge.events"
    dispatch = DispatchMode.BUFFERED
}
```

Events are logged through SLF4J at the mapped level, and structured context
is attached as SLF4J 2 key/values instead of being string-concatenated:

| Key | Value |
|---|---|
| `ilogger.eventId` | unique event id |
| `ilogger.action` | `schemaId` (`action` or `action@vN`) |
| `ilogger.entity`, `ilogger.entityId` | entity type and id, when known |
| `ilogger.actor` | actor's stable string form, when known |
| `ilogger.correlationId`, `ilogger.causationId` | ambient context, when known |

The message is the fixed text codec's line. JSON or pattern layouts can emit
the key/values as fields. The outbound direction has no `Throwable`: events
carry a `LogFailure` summary, not the original exception object, so failure
details stay in the message. Inbound conversion preserves the real throwable.

## Level mapping

| `LogLevel` | JUL | SLF4J |
|---|---|---|
| `TRACE` | `FINEST` | `TRACE` |
| `DEBUG` | `FINE` | `DEBUG` |
| `INFO` | `INFO` | `INFO` |
| `WARN` | `WARNING` | `WARN` |
| `ERROR` | `SEVERE` | `ERROR` |
| `AUDIT` | configurable, `INFO` by default | configurable, `INFO` by default |

Inbound mapping walks the framework level's numeric order: above
`SEVERE`/`ERROR` stays `ERROR`, `WARNING`/`WARN` becomes `WARN`, `CONFIG` is
treated as `DEBUG`, anything below `FINE`/`DEBUG` is `TRACE`.

Audit events are business records, not errors. Mapping them to `WARN`/`ERROR`
makes alerting noisy; keep `INFO` unless your existing stack treats `INFO` as
too chatty.

## Loop protection

Installing both directions on the same stack would otherwise recurse:
SLF4J → appender → ILogger → sink → SLF4J → appender → …

Two independent guards stop it:

1. **Reserved logger-name prefix.** Both outbound sinks log to
   `io.ilogger.bridge.events`, and both inbound bridges ignore logger names
   under `io.ilogger.bridge` by default. This works across threads and
   asynchronous dispatch. If you rename the outbound logger, add its prefix
   to `ignoredLoggerPrefixes`.
2. **Thread-local guard.** While a bridge sink is publishing, inbound bridges
   on the same thread return immediately. This covers synchronous dispatch
   before the prefix check is needed.

The prefix is a convention, not a security boundary: a third-party logger
that happens to use `io.ilogger.bridge.*` is ignored by default. Configure
`ignoredLoggerPrefixes` when your namespace overlaps.

## Shutdown and flushing

Both inbound bridges implement `flush()` by flushing the ILogger pipeline
(bounded by `timeoutMillis`) and never throw into the host logging framework.
JUL callers should `removeHandler`; Logback callers should detach or stop the
appender. The logger itself is owned by the application and must be closed by
it, after which bridge records are ignored (`hasSinks()` is false).

## Budget notes

- Inbound records build a `LogEventDraft` on the logging thread and enqueue
  it; sinks, redaction, limits and storage happen in the engine's workers.
- Redaction and limits apply to bridge events like any other: they enter at
  admission, not at the sink.
- Logging loops are the expensive failure mode, not the bridge itself. Keep
  the logger-name prefix intact.
- Do not point a bridge at an ILogger whose only sink writes back into the
  same framework under the same logger name without the prefix filter; that is
  a configuration error the guards cannot distinguish from legitimate logs.
