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
2. **Bounded memory** — backpressure is a first-class concept
   (`bufferSize`, explicit overflow policies); a flood can age out events,
   never grow memory unboundedly.
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
| `ilogger-testing` | `TestKit`: deterministic logger + sink + clock + ids |
| `ilogger-room` | Room lifecycle audit + one-line event persistence with a shipped entity/DAO (KMP, JVM/Android) |
| `ilogger-serialization` | `JsonEventCodec`: JSONL events for durable sinks |
| `ilogger-ktor` | Ktor plugin: request audit + request-scoped ambient context |
| `ksp-tests` | Processor behavior tests: compiled KSP sources + JUnit assertions |
| `ksp-diagnostics` | Negative diagnostics: intentionally invalid `@Log` fixtures, asserted verbatim |
| `examples/*` | Runnable feature walkthroughs (`basic`, `room`, `custom-sink`, `server`, `advanced-audit`) |
| `:app` (demo) | Android Compose app with an in-app event viewer |
| `:benchmark` | JMH suite (kotlinx-benchmark) + deterministic harness for overhead measurement |

## Quick start

Add the artifacts you need — published to **Maven Central**, group
`io.github.mohamed-dev-ui`. In your version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
ilogger = "0.1.0"

[libraries]
ilogger-core = { module = "io.github.mohamed-dev-ui:ilogger-core", version.ref = "ilogger" }
ilogger-annotations = { module = "io.github.mohamed-dev-ui:ilogger-annotations", version.ref = "ilogger" }
ilogger-ksp = { module = "io.github.mohamed-dev-ui:ilogger-ksp", version.ref = "ilogger" }

[plugins]
ilogger-ksp = { id = "com.google.devtools.ksp", version = "2.3.12" }
```

then in `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.ilogger.ksp)
}

dependencies {
    implementation(libs.ilogger.annotations)
    implementation(libs.ilogger.core)
    ksp(libs.ilogger.ksp)

    // optional integrations:
    // implementation(libs.ilogger.testing)
    // implementation(libs.ilogger.room)
    // implementation(libs.ilogger.serialization)
    // implementation(libs.ilogger.ktor)
}

// runtime — one sink for console during development
val logger = ILogger.create { sink(ConsoleLogSink()) }
val dao = InMemoryTaskRepository().logged(logger)
```

Full tour: [docs/01-overview.md](docs/01-overview.md) and
[docs/02-quickstart.md](docs/02-quickstart.md). The complete topic set
follows; every runtime claim below is exercised by a runnable example in
`examples/*`.

## CI & releases

- **CI** (`.github/workflows/ci.yml`) runs on every push to `main` and every
  pull request: all JVM modules build & test (libraries, KSP behavior tests,
  negative diagnostics, examples, benchmark) plus the Android demo APK, and
  a `publishToMavenLocal` smoke run.
- **Release** (`.github/workflows/release.yml`) runs when a `v*` tag is
  pushed. It runs the JVM suite, **publishes every `ilogger-*` artifact to
  Maven Central** (group `io.github.mohamed-dev-ui`, automatic release after
  Central validation) and to
  [GitHub Packages](https://maven.pkg.github.com/Mohamed-Dev-UI/ILogger)
  (`io.github.mohamed-dev-ui:<module>:<version>`), assembles the demo APK,
  and attaches all jars + APK to a GitHub release with generated notes:

  ```bash
  git tag -a v0.1.1 -m "ILogger v0.1.1"
  git push origin v0.1.1
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
  [releases/tag/v0.1.0](https://github.com/Mohamed-Dev-UI/ILogger/releases/tag/v0.1.0).

## Documentation (27 topics)

| # | Topic | # | Topic |
|---|-------|---|-------|
| 01 | [Overview & design priorities](docs/01-overview.md) | 14 | [Sinks](docs/14-sinks.md) |
| 02 | [Quickstart](docs/02-quickstart.md) | 15 | [JSON codec](docs/15-json-codec.md) |
| 03 | [KSP setup](docs/03-ksp-setup.md) | 16 | [Dispatch, backpressure & error modes](docs/16-dispatch-backpressure-error-modes.md) |
| 04 | [`@Log` annotation reference](docs/04-log-annotation-reference.md) | 17 | [Sampling](docs/17-sampling.md) |
| 05 | [Event lifecycle](docs/05-event-lifecycle.md) | 18 | [Testing with TestKit](docs/18-testing.md) |
| 06 | [Actions & schema versioning](docs/06-actions-and-schema-versioning.md) | 19 | [Room integration](docs/19-room-integration.md) |
| 07 | [Entities & id inference](docs/07-entities-and-id-inference.md) | 20 | [Ktor integration](docs/20-ktor-integration.md) |
| 08 | [Value expressions](docs/08-value-expressions.md) | 21 | [Android app guide](docs/21-android-app-guide.md) |
| 09 | [Sensitive data protection](docs/09-sensitive-data-protection.md) | 22 | [Generated code guide](docs/22-generated-code-guide.md) |
| 10 | [Extractors](docs/10-extractors.md) | 23 | [Diagnostics catalog](docs/23-diagnostics-catalog.md) |
| 11 | [Actors & ambient context](docs/11-actors-and-ambient-context.md) | 24 | [Limits & constraints](docs/24-limits-and-constraints.md) |
| 12 | [Correlation & causation](docs/12-correlation-and-causation.md) | 25 | [Performance & benchmark](docs/25-performance-and-benchmark.md) |
| 13 | [Categories & routing](docs/13-categories-and-routing.md) | 26 | [Configuration reference](docs/26-configuration-reference.md) |
| | | 27 | [Instrumentation approaches](docs/27-manual-instrumentation.md) |

Where several implementations exist, each topic lists every option with a
**🏆 Recommended** marker and a comparison table (simplicity, production
cost, runtime overhead, development speed).

## License

Apache License 2.0 — see [LICENSE](LICENSE). Every published artifact embeds
the license metadata in its POM.
