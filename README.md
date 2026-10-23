# ILogger

**Audit-grade, annotation-driven logging for Kotlin.**

[![CI](https://github.com/Mohamed-Dev-UI/ILogger/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Mohamed-Dev-UI/ILogger/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.mohamed-dev-ui/ilogger-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.mohamed-dev-ui/ilogger-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF)](https://kotlinlang.org)
[![Android API](https://img.shields.io/badge/Android-API%2028%2B-3DDC84)](https://developer.android.com)

ILogger turns annotated domain operations into structured audit events. Mark an
interface method with `@Log`, let the KSP processor generate the instrumentation,
and the framework-independent engine records **who did what, to which entity,
with which outcome** — then delivers it to the console, files, Room, HTTP,
OpenTelemetry or a custom sink.

It is built for auditability rather than debugging: events carry actor,
correlation and causation identities; delivery is bounded, observable and
isolated per sink; and anything that cannot be proven at compile time is
rejected by the processor with an actionable diagnostic.

```kotlin
object TaskActions {
    const val CREATED = "task.created"
}

interface TaskRepository {
    @Log(action = TaskActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: Task): Long
}

val logger = ILogger.create { sink(ConsoleLogSink()) }
val repository = InMemoryTaskRepository().logged(logger)   // generated adapter

withActor(Actor.user("u-42", name = "Grace")) {
    repository.insert(Task(title = "Ship the release"))
}
```

```
1783212345678 [AUDIT] task.created actor=u-42(Grace) Task(1) via TaskRepository.insert {title=Ship the release}
```

`entity` and `entityId` were inferred from the parameter and return type, the
actor came from the ambient scope, and the original method still returns its
result and rethrows failures unchanged.

## Why ILogger

- **Compile-time instrumentation.** `@Log` on the interface, not the
  implementation. Generated `.logged()` adapters infer entity ids, cannot be
  forgotten, and fail the build with a `Fix:` hint when an audit flow is
  ambiguous. Strict mode turns instrumentation mistakes into build errors.
- **Audit-grade events.** Every event can carry actor, entity, entity id,
  outcome, failure, field changes, correlation and causation ids, category and
  source, with schema/action versioning.
- **Isolated, bounded delivery.** Each sink binding has its own pipeline
  (`bufferSize`, `sinkConcurrency`, overflow policy). A slow or failing sink
  cannot delay another, and every dropped delivery is reported with a reason,
  counted in `stats()`, and optionally routed to a dead-letter sink.
- **Production controls.** Sensitive-data policies and runtime redaction,
  encryption at rest, hash-chained tamper evidence, retention/legal hold/erasure,
  sampling, rate limiting and event-size limits.
- **Integrations without coupling.** `ilogger-core` has no Android, Room, Ktor,
  serialization or HTTP dependency; optional modules add each integration.
- **Testable by design.** A deterministic `TestKit`, in-memory/failing/blocking/
  chaos fakes, a reusable `LogSinkConformance` suite and a benchmark harness.

## How it works

1. **Declare actions and annotate a boundary** — an interface method, class or
   top-level function.
2. **KSP generates an adapter** at compile time; the event shape is validated
   against the action registry and extracted values are type-checked.
3. **Wrap the implementation** with `.logged(logger)`. Calls forward unchanged;
   the adapter builds the event from the signature and ambient context, then
   hands it to the engine for dispatch to every bound sink.

## Quick start

### 1. Apply the Gradle plugin

```toml
# gradle/libs.versions.toml
[versions]
ilogger = "2.0.0"

[plugins]
ilogger = { id = "io.github.mohamed-dev-ui.ilogger", version.ref = "ilogger" }
```

```kotlin
// module build.gradle.kts
plugins {
    alias(libs.plugins.ilogger)
}

ilogger {
    strict.set(true)   // recommended for application modules
}
```

The plugin applies KSP and adds the runtime, annotations and processor at the
same version. Make sure `mavenCentral()` is visible to `pluginManagement` in
the root `settings.gradle.kts`; the marker is published there. Manual and KMP
setups are covered in [docs/03-ksp-setup.md](docs/03-ksp-setup.md).

### 2. Annotate the operation

```kotlin
object TaskActions {
    const val CREATED = "task.created"
}

interface TaskRepository {
    @Log(action = TaskActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: Task): Long
}

class InMemoryTaskRepository : TaskRepository {
    private var nextId = 0L
    override suspend fun insert(task: Task): Long = ++nextId
}
```

### 3. Wrap, then use

```kotlin
val logger = ILogger.create {
    sink(ConsoleLogSink())            // add durable sinks as needed
}

val repository = InMemoryTaskRepository().logged(logger)

withActor(Actor.user("u-42", name = "Grace")) {
    repository.insert(Task(title = "Ship the release"))
}
```

That call produces one structured event with no logging code in the
implementation. `TestKit` proves the same flow in a test:

```kotlin
val kit = TestKit.create()
val repository = InMemoryTaskRepository().logged(kit.logger)

runBlocking { repository.insert(Task(title = "Ship the release")) }

kit.sink.awaitCount(1)
val event = kit.sink.events.single()
assertEquals("task.created", event.action)
```

See [docs/02-quickstart.md](docs/02-quickstart.md) for the full walkthrough and
[docs/18-testing.md](docs/18-testing.md) for deterministic assertions.

## Artifacts

All published artifacts share the group `io.github.mohamed-dev-ui` and the same
version; the BOM aligns them without repeating versions.

```kotlin
dependencies {
    implementation(platform("io.github.mohamed-dev-ui:ilogger-bom:2.0.0"))

    implementation("io.github.mohamed-dev-ui:ilogger-core")
    implementation("io.github.mohamed-dev-ui:ilogger-annotations")

    // optional integrations — versions come from the BOM
    implementation("io.github.mohamed-dev-ui:ilogger-room")
    implementation("io.github.mohamed-dev-ui:ilogger-ktor")
    implementation("io.github.mohamed-dev-ui:ilogger-remote")
    implementation("io.github.mohamed-dev-ui:ilogger-otel")
    // ...
}
```

| Artifact | Purpose |
|---|---|
| `ilogger-core` | Engine: `ILogger`, sinks, dispatch pipelines, ambient context |
| `ilogger-annotations` | `@Log` and annotation contracts (zero dependencies) |
| `ilogger-ksp` | KSP processor: generates `.logged()` adapters, DAO facades and DI bindings |
| `ilogger-gradle-plugin` | Applies KSP, adds dependencies and exposes the `ilogger { }` DSL |
| `ilogger-bom` | Version alignment for every published module (`java-platform`) |
| `ilogger-testing` | `TestKit`, fakes, conformance suite and deterministic clock/ids |
| `ilogger-room` | Room persistence, live queries, JSONL export/import, retention, cipher hook |
| `ilogger-serialization` | `JsonEventCodec` for durable JSONL sinks |
| `ilogger-remote` | Batched HTTP shipping: gzip, retries, file spool, circuit breaker |
| `ilogger-jul` | `java.util.logging` bridges in both directions |
| `ilogger-slf4j` | SLF4J/Logback bridges with MDC and key/value context |
| `ilogger-otel` | OpenTelemetry traces, `traceparent` correlation and metrics |
| `ilogger-ktor` | Server request audit, client audit and context propagation |
| `ilogger-android` | Logcat sink, lifecycle flush, crash handoff, WorkManager shipping |

**Manual setup** (KMP or explicit KSP control): apply KSP yourself and declare
`ilogger-annotations`, `ilogger-core` and `ksp("io.github.mohamed-dev-ui:ilogger-ksp")`.
The Gradle plugin wires JVM and Android targets only.

`ilogger-cli` is a runnable application, not a published library. Build its
distribution with `./gradlew :ilogger-cli:installDist`; see
[docs/36-cli.md](docs/36-cli.md).

## Requirements

| Component | Tested version |
|---|---|
| Kotlin / KSP | 2.2.10 / 2.3.12 |
| Gradle | 9.6.0 |
| JDK | 17 (all jars target Java 17 bytecode) |
| Android | minSdk 28, compileSdk 37 |

JVM and Android are fully supported. Non-JVM Kotlin Multiplatform targets are
not published yet. Newer toolchain versions usually work but are not claimed
until CI covers them; the canonical matrix is
[docs/38-compatibility.md](docs/38-compatibility.md).

## Documentation

Start with these:

| Guide | Contents |
|---|---|
| [01 — Overview & design](docs/01-overview.md) | Architecture, design priorities, event model |
| [02 — Quickstart](docs/02-quickstart.md) | End-to-end setup in five minutes |
| [03 — KSP setup](docs/03-ksp-setup.md) | Manual wiring, KMP and processor options |
| [04 — `@Log` reference](docs/04-log-annotation-reference.md) | Every annotation attribute and default |
| [11 — Actors & ambient context](docs/11-actors-and-ambient-context.md) | Actor attribution, correlation, causation |
| [14 — Sinks](docs/14-sinks.md) | Console, file, Room, HTTP and custom sinks |
| [18 — Testing](docs/18-testing.md) | `TestKit`, fakes and conformance testing |
| [39 — Migrating to ILogger](docs/39-migration.md) | Adopting an existing codebase |
| [40 — Cheatsheet](docs/40-cheatsheet.md) | The whole surface on one page |

<details>
<summary>All 40 documentation topics</summary>

| # | Topic | # | Topic |
|---|-------|---|-------|
| 01 | [Overview & design priorities](docs/01-overview.md) | 14 | [Sinks](docs/14-sinks.md) |
| 02 | [Quickstart](docs/02-quickstart.md) | 15 | [JSON codec](docs/15-json-codec.md) |
| 03 | [KSP setup](docs/03-ksp-setup.md) | 16 | [Dispatch, backpressure & error modes](docs/16-dispatch-backpressure-error-modes.md) |
| 04 | [`@Log` annotation reference](docs/04-log-annotation-reference.md) | 17 | [Sampling](docs/17-sampling.md) |
| 05 | [Event lifecycle](docs/05-event-lifecycle.md) | 18 | [Testing with TestKit](docs/18-testing.md) |
| 06 | [Actions & schema versioning](docs/06-actions-and-schema-versioning.md) | 19 | [Room integration](docs/19-room-integration.md) |
| 07 | [Entities & id inference](docs/07-entities-and-id-inference.md) | 20 | [Ktor client + server](docs/20-ktor-integration.md) |
| 08 | [Value expressions](docs/08-value-expressions.md) | 21 | [Android app guide](docs/21-android-app-guide.md) |
| 09 | [Sensitive data protection](docs/09-sensitive-data-protection.md) | 22 | [Generated code guide](docs/22-generated-code-guide.md) |
| 10 | [Extractors](docs/10-extractors.md) | 23 | [Diagnostics catalog](docs/23-diagnostics-catalog.md) |
| 11 | [Actors & ambient context](docs/11-actors-and-ambient-context.md) | 24 | [Limits & constraints](docs/24-limits-and-constraints.md) |
| 12 | [Correlation & causation](docs/12-correlation-and-causation.md) | 25 | [Performance & benchmark](docs/25-performance-and-benchmark.md) |
| 13 | [Categories & routing](docs/13-categories-and-routing.md) | 26 | [Configuration reference](docs/26-configuration-reference.md) |
| | | 27 | [Instrumentation approaches](docs/27-manual-instrumentation.md) |
| | | 28 | [Android module](docs/28-android-module.md) |
| | | 29 | [Delivery pipeline & quality gates](docs/29-delivery-and-quality-gates.md) |
| | | 30 | [Integrity: tamper-evident audit trails](docs/30-integrity.md) |
| | | 31 | [Encryption at rest](docs/31-encryption-at-rest.md) |
| | | 32 | [Retention, legal hold & erasure](docs/32-compliance.md) |
| | | 33 | [Remote HTTP sink](docs/33-remote-sink.md) |
| | | 34 | [Logging-stack bridges](docs/34-logging-bridges.md) |
| | | 35 | [OpenTelemetry: traces & metrics](docs/35-opentelemetry.md) |
| | | 36 | [CLI & audit tooling](docs/36-cli.md) |
| | | 37 | [Kotlin Multiplatform](docs/37-kmp.md) |
| | | 38 | [Compatibility matrix](docs/38-compatibility.md) |
| | | 39 | [Migrating to ILogger](docs/39-migration.md) |
| | | 40 | [Cheatsheet](docs/40-cheatsheet.md) |

</details>

## Examples

| Example | Demonstrates |
|---|---|
| `:examples:basic` | Annotations, console sink and generated adapters |
| `:examples:room` | Room persistence and live audit queries |
| `:examples:custom-sink` | A custom `LogSink` with delivery accounting |
| `:examples:server` | Ktor server/client audit and trace propagation |
| `:examples:advanced-audit` | Integrity, encryption, retention and compliance |
| `:examples:gradle-plugin` | A consumer build using the published Gradle plugin |
| `:app` | Android reference app: every screen drives the real engine, with copy-ready recipes |

Run any JVM example with `./gradlew :examples:basic:run` (replace the module).
The Android app builds with `./gradlew :app:assembleDebug`; a signed debug APK is
attached to every GitHub release. The app guide is
[docs/21-android-app-guide.md](docs/21-android-app-guide.md).

## Releases

- **Maven Central** is the primary distribution: every published module, the
  Gradle plugin and its plugin marker.
- **GitHub Packages** mirrors the same coordinates; authentication is required.
- Every release attaches all jars, sources, javadocs, the API docs bundle and
  the demo APK to the [latest GitHub release](https://github.com/Mohamed-Dev-UI/ILogger/releases/latest).
- Release notes are maintained in [CHANGELOG.md](CHANGELOG.md).

Publishing a release is driven by a version tag; the workflow runs the full JVM
suite, publishes to Maven Central and GitHub Packages, and creates the release:

```bash
git tag -a v2.0.0 -m "ILogger v2.0.0"
git push origin v2.0.0
```

<details>
<summary>Consuming from GitHub Packages</summary>

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/Mohamed-Dev-UI/ILogger")
        credentials {
            username = "<github-user>"
            password = "<personal-access-token>"
        }
    }
}
```

</details>

## Building from source

```bash
./gradlew check                              # tests, API dumps, coverage floors
./gradlew -p ilogger-gradle-plugin test      # Gradle plugin TestKit suite
./gradlew :examples:basic:run                # a real consumer, end to end
./gradlew :app:assembleDebug                 # Android reference app
./gradlew dokkaGenerate                      # API documentation
```

## Contributing

Issues and pull requests are welcome. Before opening a PR, run `./gradlew check`
and the plugin TestKit suite; the quality gates and delivery pipeline are
documented in
[docs/29-delivery-and-quality-gates.md](docs/29-delivery-and-quality-gates.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE). Every published artifact carries
the license metadata in its POM.
