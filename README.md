# ILogger

Annotation-driven, audit-grade logging for Kotlin. Mark domain operations with
`@Log`, let the KSP processor generate the instrumentation, and get structured
events from a framework-independent core — with compile-time validation of
everything it cannot prove.

```kotlin
object TaskActions {
    const val CREATED = "task.created"
}

interface TaskRepository {
    @Log(action = TaskActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: Task): Long
}

// one line attaches the generated adapter — events record, calls forward:
val dao = InMemoryTaskRepository().logged(logger)
```

Because auditing is a **cross-cutting reliability concern**, ILogger's
priorities are, in order:

1. **Audit correctness** — *who* did *what* to *which entity*, with outcome,
   actor attribution, correlation/causation identities, and failure payloads
   that never swallow exceptions.
2. **Bounded, isolated delivery** — one bounded pipeline per sink binding
   (`bufferSize`, `sinkConcurrency`, explicit overflow policies); a slow or
   failing sink cannot delay the others, and every lost delivery is reported
   with a `DropReason` (`onDrop`, optional dead-letter sink) and visible in
   `stats()`.
3. **Zero coupling in core** — no Android / Room / Ktor / serialization in
   `ilogger-core`; optional integrations ship as separate modules.
4. **Compile-time guarantees** — actors, entities and ids are *proven* from
   signatures; ambiguous flows do not compile, always with a "Fix:" hint.

## Modules

| Module | Purpose |
|---|---|
| `ilogger-annotations` | `@Log` & friends: annotation contracts (no dependencies) |
| `ilogger-core` | Engine: `ILogger`, sinks, dispatch, ambient context (coroutines only) |
| `ilogger-ksp` | KSP processor: generates the `.logged()` adapters, database facades and optional Hilt/Koin bindings |
| `ilogger-gradle-plugin` | One-line setup: applies KSP, adds the runtime/processor dependencies and exposes the typed `ilogger { }` DSL |
| `ilogger-bom` | Version alignment for every published module (`java-platform`) |
| `ilogger-testing` | `TestKit` (deterministic logger + sink + clock + ids), fakes (in-memory, failing, blocking, seeded chaos), and a reusable `LogSinkConformance` suite for new sinks |
| `ilogger-room` | Room lifecycle audit + one-line event persistence with a shipped entity/DAO, live filtered queries, JSONL export/import, retention scheduling and payload cipher hook (KMP, JVM/Android) |
| `ilogger-serialization` | `JsonEventCodec`: JSONL events for durable sinks |
| `ilogger-remote` | Batched HTTP shipping: gzip JSONL, retries/backoff, durable file spool, circuit breaker, dead letter, transport SPI (JDK HTTP; OkHttp/Ktor adapters) |
| `ilogger-otel` | OpenTelemetry: `traceId`/`spanId` correlation including inbound `traceparent`, events-as-spans export, engine + sink metrics into any OTel registry (API `compileOnly`) |
| `ilogger-jul` | `java.util.logging` bridges: `JulLogHandler` into ILogger and `JulLogSink` out to JUL |
| `ilogger-slf4j` | SLF4J/Logback bridges: `ILoggerAppender` into ILogger and `Slf4jLogSink` out to SLF4J, with MDC and key/value context |
| `ilogger-ktor` | Ktor plugins: server request audit, outbound client audit, correlation/`traceparent` propagation |
| `ilogger-android` | Android: Logcat sink, process-lifecycle flush, crash handoff, StrictMode-safe file sink, session actor, optional WorkManager shipping |
| `ilogger-cli` | Audit tooling (application, not published): `tail`, `verify` hash chains, `export` CSV/JSON from JSONL or Room, `schema` registry inspection, `replay`/re-encrypt |
| `ksp-tests` | Processor behavior tests: compiled KSP sources + JUnit assertions |
| `ksp-diagnostics` | Negative diagnostics: intentionally invalid `@Log` fixtures, asserted verbatim |
| `ksp-diagnostics-warnings` | Warning families: duplicate/undeclared actions, unused `@LogDao`, sensitive actor, suppression |
| `ksp-diagnostics-options` | Opt-in rules: `require-actor` and `sink-categories`, plus compliant no-warning fixtures |
| `examples/*` | Runnable feature walkthroughs (`basic`, `room`, `custom-sink`, `server`, `advanced-audit`, `gradle-plugin`) |
| `:app` (reference app) | Full Android tour: Room + JSONL sinks, annotated repo/DAO, protection, engine lab, TestKit and copy-ready recipes |
| `:benchmark` | JMH suite (kotlinx-benchmark) + deterministic harness for overhead measurement |

The Android reference app is the fastest way to learn the library: every
screen drives the real engine, and the Recipes screen contains copy-ready
Gradle, Hilt, Room, Koin, Ktor, sink and testing snippets. See
[docs/21-android-app-guide.md](docs/21-android-app-guide.md).

## Quick start

Add the artifacts you need — published to **Maven Central**, group
`io.github.mohamed-dev-ui`. The Gradle plugin wires KSP, the runtime, the
annotations and the processor in one line:

```toml
[versions]
ilogger = "2.0.0"

[plugins]
ilogger = { id = "io.github.mohamed-dev-ui.ilogger", version.ref = "ilogger" }
```

```kotlin
plugins {
    alias(libs.plugins.ilogger)
}

ilogger {
    strict.set(true)   // recommended for application modules
}

// runtime — one sink for console during development
val logger = ILogger.create { sink(ConsoleLogSink()) }
val dao = InMemoryTaskRepository().logged(logger)
```

Add the BOM for version-aligned optional integrations:

```kotlin
dependencies {
    implementation(platform("io.github.mohamed-dev-ui:ilogger-bom:2.0.0"))
    // implementation("io.github.mohamed-dev-ui:ilogger-testing")
    // implementation("io.github.mohamed-dev-ui:ilogger-room")
    // implementation("io.github.mohamed-dev-ui:ilogger-serialization")
    // implementation("io.github.mohamed-dev-ui:ilogger-remote")
    // implementation("io.github.mohamed-dev-ui:ilogger-jul")
    // implementation("io.github.mohamed-dev-ui:ilogger-slf4j")
    // implementation("io.github.mohamed-dev-ui:ilogger-ktor")
    // implementation("io.github.mohamed-dev-ui:ilogger-otel")
}
```

`ilogger-cli` is a runnable application rather than a library, so it is not
published to Maven: build the distribution with
`./gradlew :ilogger-cli:installDist` (see [docs/36-cli.md](docs/36-cli.md)).

Prefer manual wiring (KMP today, or explicit KSP control)? See
[docs/03-ksp-setup.md](docs/03-ksp-setup.md).

Full tour: [docs/01-overview.md](docs/01-overview.md) and
[docs/02-quickstart.md](docs/02-quickstart.md). The complete topic set
follows; every runtime claim below is exercised by a runnable example in
`examples/*`.

## CI & releases

- **CI** (`.github/workflows/ci.yml`) runs on every push to `main` and every
  pull request: all JVM modules build & test (libraries, Gradle plugin,
  KSP behavior tests, negative diagnostics, examples, benchmark) plus the
  Android demo APK, and a `publishToMavenLocal` smoke run.
- **Release** (`.github/workflows/release.yml`) runs when a `v*` tag is
  pushed. It runs the JVM suite, **publishes every `ilogger-*` artifact
  (including the Gradle plugin and marker) to Maven Central** (group
  `io.github.mohamed-dev-ui`, automatic release after Central validation) and
  to [GitHub Packages](https://maven.pkg.github.com/Mohamed-Dev-UI/ILogger)
  (`io.github.mohamed-dev-ui:<module>:<version>`), assembles the demo APK,
  and attaches all jars + APK to a GitHub release with generated notes:

  ```bash
  git tag -a v2.0.0 -m "ILogger v2.0.0"
  git push origin v2.0.0
  ```
- **Changelog** lives in [CHANGELOG.md](CHANGELOG.md).
- **Consuming artifacts from GitHub Packages** (requires a GitHub token —
  Packages are always authenticated):

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

  Prefer no credentials? The same jars are attached to every GitHub release:
  [releases/tag/v2.0.0](https://github.com/Mohamed-Dev-UI/ILogger/releases/tag/v2.0.0).

## Documentation (40 topics)

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

Where several implementations exist, each topic lists every option with a
**🏆 Recommended** marker and a comparison table (simplicity, production
cost, runtime overhead, development speed).

## License

Apache License 2.0 — see [LICENSE](LICENSE). Every published artifact embeds
the license metadata in its POM.
