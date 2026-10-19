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
| `ilogger-ksp` | KSP processor: generates the `.logged()` adapters |
| `ilogger-testing` | `TestKit`: deterministic logger + sink + clock + ids |
| `ilogger-room` | Room database lifecycle audit (KMP, JVM/Android) |
| `ilogger-serialization` | `JsonEventCodec`: JSONL events for durable sinks |
| `ilogger-ktor` | Ktor plugin: request audit + request-scoped ambient context |
| `ksp-tests` | Processor behavior tests: compiled KSP sources + JUnit assertions |
| `ksp-diagnostics` | Negative diagnostics: intentionally invalid `@Log` fixtures, asserted verbatim |
| `examples/*` | Runnable feature walkthroughs (`basic`, `room`, `custom-sink`, `server`, `advanced-audit`) |
| `:app` (demo) | Android Compose app with an in-app event viewer |
| `:benchmark` | JMH suite (kotlinx-benchmark) + deterministic harness for overhead measurement |

## Quick start

```kotlin
dependencies {
    implementation("io.ilogger:ilogger-annotations:0.1.0")
    implementation("io.ilogger:ilogger-core:0.1.0")
    ksp("io.ilogger:ilogger-ksp:0.1.0")
}

// runtime — one sink for console during development
val logger = ILogger.create { sink(ConsoleLogSink()) }
val dao = InMemoryTaskRepository().logged(logger)
```

Full tour: [docs/01-overview.md](docs/01-overview.md) and
[docs/02-quickstart.md](docs/02-quickstart.md). The complete topic set
follows; every runtime claim below is exercised by a runnable example in
`examples/*`.

## Documentation (25 topics)

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
| 13 | [Categories & routing](docs/13-categories-and-routing.md) | | |

## License

Apache License 2.0 — see [LICENSE](LICENSE). Every published artifact embeds
the license metadata in its POM.
