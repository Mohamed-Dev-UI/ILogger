# 40. Cheatsheet

One page for the 90% path. Every section links to the detailed topic.

## Setup

```kotlin
plugins { alias(libs.plugins.ilogger) }        // KSP + runtime + processor
ilogger { strict.set(true) }                   // app modules: fail on ambiguity

dependencies {
    implementation(platform("io.github.mohamed-dev-ui:ilogger-bom:2.0.0"))
    implementation("io.github.mohamed-dev-ui:ilogger-serialization")
    implementation("io.github.mohamed-dev-ui:ilogger-room")        // optional
    implementation("io.github.mohamed-dev-ui:ilogger-android")     // Android
}
```

Manual wiring, KMP limits and the plugin DSL: [03](03-ksp-setup.md),
[37](37-kmp.md). Versions: [38](38-compatibility.md).

## Annotate

```kotlin
const val TASK_CREATED = "task.created"                  // stable action id

interface TaskRepository {
    @Log(
        action = TASK_CREATED,                            // domain.pastTense
        entity = Task::class,                             // entity type
        entityId = "taskId",                              // expression, if not inferable
        metadata = [LogMeta(value = "taskId", name = "task.id"), LogMeta("title")],
        changes = [LogChange(field = "status", from = "old.status", to = "new.status")],
    )
    suspend fun create(taskId: String, title: String, old: Task, new: Task): Task

    @Log(action = TASK_RENAMED, autoChanges = AutoChanges.ALL)
    suspend fun rename(old: Task, updated: Task): Boolean  // diffs eligible fields
}
```

- `onlyIfChanged = true` on a `LogChange` drops the pair when both values are
  equal; `autoChanges = ALL` records every eligible changed field. Both are
  off per call unless requested; `ilogger { autoChanges.set(true) }` makes
  auto-capture the module default ([04](04-log-annotation-reference.md)).

- `@Log` works on interfaces/classes/functions; the processor generates
  `.logged(logger)`. `@NoLog` opts out, and class-level defaults come from
  `@LogConfig` ([04](04-log-annotation-reference.md)).
- Values are restricted to loggable scalars; select fields explicitly. Never
  log secrets — mark them `@LogSensitive` or omit them ([08](08-value-expressions.md),
  [09](09-sensitive-data-protection.md)).
- Name `id`/`<entity>Id` and the id is inferred; otherwise set `entityId`.
- Dynamic action names are rejected at compile time.

## Attach

```kotlin
val logger = ILogger.create {
    sink(ConsoleLogSink())                 // development
    sink(TextFileSink(Path.of("audit.jsonl"), JsonEventCodec))   // positional file+codec
    dispatch = DispatchMode.BUFFERED       // INLINE for tests/CLI-like tools
}

logger.use {
    val repo = RoomTaskRepository(db).logged(logger)   // generated adapter
}
```

Ambient actor/correlation rides the coroutine context:

```kotlin
withLogContext(LogContext(actor = Actor.user("u-1"), correlationId = requestId)) {
    repo.create(...)      // actor and correlation on every nested event
}
```

[11](11-actors-and-ambient-context.md), [12](12-correlation-and-causation.md).

## Engine knobs

| Builder property / method | Default | What it does |
|---|---|---|
| `enabled` | `true` | Hot switch; disabled loggers allocate nothing |
| `minLevel` | `TRACE` | Drops lower-level diagnostic events |
| `dispatch` | `BUFFERED` | `INLINE`, `BUFFERED`, `ASYNC` ([16](16-dispatch-backpressure-error-modes.md)) |
| `bufferSize` / `batchSize` | 4096 / 128 | Per-binding pipeline capacity and batch size |
| `overflow` | `DROP_NEWEST` | `BLOCK`, `DROP_NEWEST`, `DROP_OLDEST` |
| `sinkConcurrency` | 1 | Parallel writes per sink binding |
| `onDrop { }` / `deadLetter(sink)` | — | Lost-delivery accounting ([16](16-dispatch-backpressure-error-modes.md)) |
| `sampling(level to rate)` / `sampler` | none | Per-level / contextual sampling ([17](17-sampling.md)) |
| `strictMode` | `OFF` | `WARN` or `REJECT` runtime validation ([04](04-log-annotation-reference.md)) |
| `limits` | none | Byte/string/collection caps with `REJECT` or `TRUNCATE` ([24](24-limits-and-constraints.md)) |
| `redaction` | none | Key/path redaction, masking, hashing ([09](09-sensitive-data-protection.md)) |
| `extractor` / `valueAdapter` | none | Work with DI-owned objects and types ([10](10-extractors.md)) |
| `enricher` | none | Admission-time metadata (OTel traces, host, build) ([35](35-opentelemetry.md)) |
| `clock` / `idGenerator` | system | Deterministic tests and audit sequencing |
| `onErrorMode(level, mode)` | `RETRY` | `PROPAGATE`, `RETRY`, `DROP`, `DEAD_LETTER` |

## Sinks

| Sink | Use |
|---|---|
| `ConsoleLogSink` | Development, `INLINE` |
| `TextFileSink` | Durable JSONL; rotation, gzip, fsync, encryption |
| `CompositeLogSink` | Fan-out with one report |
| `HashChainSink` | Tamper-evident stamps ([30](30-integrity.md)) |
| `RemoteLogSink` (`ilogger-remote`) | Batched HTTP with spool, retries, circuit breaker ([33](33-remote-sink.md)) |
| `RoomLogTarget` (`ilogger-room`) | Queryable table, export, retention ([19](19-room-integration.md)) |
| `LogcatSink` (`ilogger-android`) | Android logcat, lifecycle flush ([28](28-android-module.md)) |
| `OtelSpanLogSink` / `OtelSinkMetrics` | Traces and metrics ([35](35-opentelemetry.md)) |
| `JulLogSink` / `Slf4jLogSink` | Feed an existing stack ([34](34-logging-bridges.md)) |

Routing by category: `sink(sink, category = "audit")` + `category` on `@Log`
([13](13-categories-and-routing.md)).

## Compliance stack

```kotlin
// Append-only, verifiable, encrypted at rest
val key = EncryptionKey("2026-q4", aesKeyBytes)
val sink = TextFileSink(
    auditFile,
    JsonEventCodec,
    cipher = AesGcmCipher(key),
)
logger = ILogger.create {
    sink(HashChainSink(sink, SigningKey("2026-q4", HmacTrailSigner(hmacKeyBytes))))
}
```

| Concern | Tool |
|---|---|
| Tamper evidence | `HashChainSink` + `TrailVerifier` ([30](30-integrity.md)) |
| Encryption at rest | `AesGcmCipher`, `RoomLogTarget(payloadCipher=…)` ([31](31-encryption-at-rest.md)) |
| Retention / erasure / hold | `RoomLogRetention`, erasure APIs ([32](32-compliance.md)) |
| Inspection | `ilogger-cli` tail/verify/export/schema/replay ([36](36-cli.md)) |

## Testing

```kotlin
val kit = TestKit.create()                       // INLINE + FixedClock + ids e1, e2…
val repo = FakeTaskRepository().logged(kit.logger)

repo.create("42", "Write docs")

val event = kit.assertLogged(action = "task.created").single()
assertEquals("42", event.entityId)
kit.assertNoSensitive()
```

`TestKit`, `RecordingSink`, `ChaoticLogSink`, golden snapshots and the
`LogSinkConformance` suite: [18](18-testing.md).

## CLI

```bash
./gradlew :ilogger-cli:installDist
ilogger tail audit.jsonl --level WARN --last 1h
ilogger verify audit.jsonl --hmac-key prod=file:/run/secrets/audit.key --json
ilogger export app.db --format csv --output audit.csv
ilogger replay audit.jsonl --decrypt-key old=... --to jsonl:rotated.jsonl \
  --encrypt-key new=... --overwrite
```

[36](36-cli.md).

## Where each feature runs in this repository

| Feature | Runnable proof |
|---|---|
| Annotations, generated adapters, correlation | `examples/basic` |
| Gradle plugin, one-line setup | `examples/gradle-plugin` + plugin TestKit |
| Custom sinks, per-sink pipelines, failure isolation, sampling | `examples/custom-sink`, `examples/advanced-audit` |
| Room persistence, retention, lifecycle audit | `examples/room`, `:app` |
| Ktor server/client audit | `examples/server`, `ilogger-ktor` tests |
| Android Logcat, lifecycle flush, crash handoff | `:app`, `ilogger-android` instrumented tests |
| Integrity, encryption, limits, redaction, rate limiting | `ilogger-core` tests |
| Remote shipping (retries, spool, circuit breaker) | `ilogger-remote` tests (MockWebServer) |
| JUL / SLF4J bridges | `ilogger-jul` / `ilogger-slf4j` tests |
| OpenTelemetry traces & metrics | `ilogger-otel` tests |
| CLI commands and key handling | `ilogger-cli` tests |
| KSP diagnostics and options | `ksp-diagnostics*` fixture modules |

## Reading order

Quickstart [02](02-quickstart.md) → annotation reference [04](04-log-annotation-reference.md)
→ values [08](08-value-expressions.md) → sinks/routing [13](13-categories-and-routing.md),
[14](14-sinks.md) → config [26](26-configuration-reference.md) → integrations
[19](19-room-integration.md), [20](20-ktor-integration.md), [28](28-android-module.md)
→ testing [18](18-testing.md) → adoption [39](39-migration.md).
