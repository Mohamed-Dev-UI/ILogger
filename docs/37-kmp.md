# 37. Kotlin Multiplatform: current support and strategy

**Bottom line: ILogger 1.x ships JVM 17 artifacts.** They run on the JVM
(servers, CLI tools, tests) and on Android (API 28+). iOS, JS, Wasm and Kotlin
Native targets are **not published yet**; the multiplatform source split is
tracked as G-27 and scheduled for v2.0. This page explains what works today,
how to structure a KMP application around that limit, and what will change.

## Support matrix

| Artifact | JVM 17 | Android 28+ | iOS / JS / Native |
|---|:---:|:---:|:---:|
| `ilogger-annotations` | ✅ | ✅ | — (planned v2.0) |
| `ilogger-core` | ✅ | ✅ | — (planned v2.0) |
| `ilogger-ksp` (processor) | ✅ | ✅ | — |
| `ilogger-serialization` | ✅ | ✅ | — (planned v2.0) |
| `ilogger-testing` | ✅ | ✅ | — (planned v2.0) |
| `ilogger-room` | ✅ | ✅ | — |
| `ilogger-ktor` | ✅ | ✅ | — |
| `ilogger-remote` | ✅ | ✅ | — |
| `ilogger-jul` / `ilogger-slf4j` | ✅ | ✅ | — |
| `ilogger-otel` | ✅ | ✅ | — |
| `ilogger-android` | — | ✅ | — |
| `ilogger-cli` (application) | ✅ | — | — |

“Android ✅” means the JVM artifact is consumable from Android source sets
(`minSdk 28`, Java 17 bytecode); `ilogger-android` is the Android-specific
module ([28](28-android-module.md)).

## Why the artifacts are JVM-only today

The engine's portable surface — `LogEvent`, `Actor`, annotations, sampling
policies, the sink pipeline — is written in plain Kotlin. The remaining
platform dependencies are deliberately isolated but not yet behind
`expect/actual`:

- ambient context storage (thread-local),
- clock and monotonic time,
- event-id generation and hashing,
- atomic counters and dispatchers,
- file IO (only in the optional sinks).

Publishing non-JVM targets without replacing those would ship an API that
compiles but cannot run. G-27 splits core into `commonMain` + platform source
sets, adds `expect/actual` implementations, and validates a sample on JVM plus
at least one non-JVM target before the artifacts are published.

## Structuring a KMP application today

Use a **port in `commonMain`, adapter in `jvmMain`/`androidMain`**. Annotated
interfaces and the generated `.logged()` adapters live on the JVM side;
`commonMain` never references ILogger types.

```kotlin
// commonMain — no ILogger dependency
data class AuditRecord(
    val action: String,
    val entity: String?,
    val entityId: String?,
    val metadata: Map<String, Any?>,
)

interface AuditSink {
    suspend fun record(record: AuditRecord)
}
```

```kotlin
// jvmMain (or androidMain) — ILogger lives here
class ILoggerAudit(private val logger: ILogger) : AuditSink {
    override suspend fun record(record: AuditRecord) {
        logger.record(
            action = record.action,
            entity = record.entity,
            entityId = record.entityId,
            metadata = record.metadata,
        )
    }
}
```

```kotlin
// shared use case stays platform-neutral
class CloseTask(private val audit: AuditSink) {
    suspend fun invoke(taskId: String) {
        // ... domain work ...
        audit.record(AuditRecord("task.closed", "Task", taskId, mapOf("reason" to "done")))
    }
}
```

```kotlin
// jvmMain / androidMain dependencies
dependencies {
    implementation("io.github.mohamed-dev-ui:ilogger-core:2.0.0")
    implementation("io.github.mohamed-dev-ui:ilogger-serialization:2.0.0")
    // KSP + annotations only in the JVM-facing source set
    implementation("io.github.mohamed-dev-ui:ilogger-annotations:2.0.0")
    ksp("io.github.mohamed-dev-ui:ilogger-ksp:2.0.0")
}
```

For KMP projects the Gradle plugin is not applied yet (it wires JVM/Android
targets only); use the manual `ksp(...)` wiring in the JVM or Android target.
See [03](03-ksp-setup.md).

### What goes where

| Source set | Allowed |
|---|---|
| `commonMain` | Domain types, action-name constants, your audit port, correlation ids passed as strings |
| `jvmMain` | `ILogger`, `@Log`, generated adapters, JSONL/file sinks, Ktor server/client plugins, CLI-facing code |
| `androidMain` | Everything in `jvmMain`, plus `ilogger-android` (Logcat, lifecycle flush) |
| `commonTest` | Pure domain tests with a fake `AuditSink` |
| `jvmTest` / `androidUnitTest` | `ilogger-testing` (`TestKit`), codec tests, sink assertions |

Integrations stay on the JVM side: Ktor and Room are JVM libraries today
(their own KMP support does not make ILogger's glue multiplatform). The same
applies to SLF4J/JUL and OpenTelemetry.

## Testing a KMP project

- `commonTest` asserts domain behavior through your port; use a hand-written
  fake `AuditSink` (no ILogger dependency).
- `jvmTest` uses `TestKit` to prove the adapter emits the right action,
  actor and metadata. The in-memory logger is deterministic, so the same test
  runs on CI without a device.
- Android instrumented tests exercise `ilogger-android` only; they are not
  needed for common code.

## What v2.0 changes

G-27 will publish `annotations`, `core`, `testing` and `serialization` for
JVM, Android, iOS/Native and JS/Wasm, with `expect/actual` implementations for
clock, ids, hashing, context, atomics and dispatchers. The intent is to keep
the event model and DSL unchanged — moving a `jvmMain` adapter into
`commonMain` should be additive, not a rewrite. Until that release, treat the
JVM-only boundary as a hard constraint, not an inconvenience to work around:
adding a JVM artifact to `commonMain` fails Gradle variant resolution, and
forcing it produces metadata that cannot run on non-JVM targets.

Track the plan in [GOALS.md](../GOALS.md) (G-27) and the support matrix in
[38-compatibility.md](38-compatibility.md).
