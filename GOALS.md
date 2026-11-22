# ILogger Goals & Roadmap

> Status: planning · 2026-11-03 · baseline `5697555` (released **2.0.0** on 2026-10-23,
> plus the unreleased `@Log(autoChanges)` and benchmark-gate work)
>
> Scope: `ilogger-*` artifacts, the KSP processor, the Gradle plugin, integrations,
> tooling, docs and CI. This supersedes the 2026-10-21 revision, whose items
> G-01…G-44 are closed and are summarized in Appendix A.
>
> Every numbered statement below was checked against the tree at HEAD, not against
> the previous roadmap or the docs. Where docs, code and roadmap disagree, the code
> wins and the disagreement is itself a tracked weakness (section 3.1).
>
> Sections 3 and 4 fold in two surveys: the JVM/Android parity pass (Logback 1.6.x,
> Log4j2 2.26/3.0-beta, tinylog 2.8, SLF4J 2.0 + `kotlinx-coroutines-slf4j`,
> kotlin-logging, Timber, Kermit, Logbook, Envers/Spring Data auditing/Javers,
> Spring Boot 4.x) and the non-JVM/standards pass (Serilog, NLog, pino, structlog,
> loguru, zap, `log/slog`, zerolog, tracing, swift-log, OTLP/ECS/CLEF/GELF/syslog).
> Versions are the ones the claims were checked against, per rule 11 of section 5.
>
> The plan is organized as ten phases. **Each phase ends in a blocking test gate**
> (section 5): a stated case matrix that must be covered 100%, plus property,
> mutation and benchmark floors. A phase is not done when its features work; it is
> done when its matrix is green, deterministic and enforced in CI.

---

## 1. Objective

Make ILogger the default structured logging **and audit** library for Kotlin —
JVM, Android and Kotlin Multiplatform — on five axes:

- **Fast** — nanosecond-cost when disabled, zero allocation on rejected paths,
  byte-bounded memory, no global lock on the hot admission path, no head-of-line
  blocking between unrelated sinks.
- **Complete** — covers everything a logging facade must offer (named loggers,
  levels, lazy messages, markers, caller data, scopes, MDC/NDC-equivalent context,
  per-sink filtering, runtime reconfiguration) *and* everything an audit platform
  must offer (actors, entities, outcomes, integrity, retention, erasure, evidence
  export).
- **Customizable** — every stage of the pipeline is an SPI a consumer can replace:
  context, ids, clock, processors, filters, enrichment, redaction, limits,
  routing, layout/encoding, transport, keys, anchoring, health, self-diagnostics.
  Nothing forces a fork.
- **Reliable** — no silent loss, total accounting, true flush/close barriers,
  idempotent retries, crash- and power-loss durability that matches the documented
  claim, safe shutdown on every platform.
- **Adoptable** — compile-time proof with actionable diagnostics, one-line setup,
  drop-in bridges, standards-shaped output (OTLP/ECS/Loki), testable by default,
  tooling that makes the data readable without writing code.

Feature count is not the goal. Adoption friction, provable delivery and parity
with the mature libraries of each ecosystem are (section 4).

---

## 2. Verified baseline (2.0.0 + unreleased)

| Area | Shipped today (evidence) |
|---|---|
| Artifacts | 14 published (`core, annotations, ksp, gradle-plugin, bom, testing, room, serialization, remote, jul, slf4j, otel, ktor, android`) + unpublished `ilogger-cli` — README.md:158-201 |
| Platform | JVM 17 + Android (aar) only. Every module applies `org.jetbrains.kotlin.jvm`; zero non-JVM targets — ilogger-room/build.gradle.kts:2, docs/37-kmp.md |
| Logger facade | `ILogger` with `record`/`recordNow`/`recordBlocking`/`tryRecord`/`recordOrDrop`/drafts, diagnostics `trace…error(String, vararg Pair)`, `isLoggable`, `hasSinks`, `isBacklogged`, `availableCapacity`, `flush(timeout): FlushReport`, `awaitIdle`, `stats()`, `closeAndReport()` — ILogger.kt:375-818 |
| Event model | `LogEvent` (17 fields, defensive copies), `LogEventDraft` (20 fields), `Actor`/`ActorType`, `Outcome{START,SUCCESS,FAILURE}`, `LogFailure` (8 frames default), `FieldChange`, reserved `ilogger.*` key namespace — LogEvent.kt, EventDetails.kt |
| Engine | 1 951-line `DefaultILogger`: per-binding `BindingPipeline` + `ArrayDeque` (capacity `bufferSize`), lazy workers on `Dispatchers.IO.limitedParallelism(sinkConcurrency)`, watermark flush, `DropReason` (9 values), `DeadLetterPipeline` (capacity `bufferSize/4`, min 16), recursion depth guard 32, error-mode defaults AUDIT/ERROR→RETRY others→DROP — DefaultILogger.kt |
| Controls | `StrictMode`, `EventLimits` (REJECT/TRUNCATE), `RedactionPolicy` + `RedactionRules`, `LogEventEnricher`, `ValueAdapterRegistry`, `ExtractorRegistry`, `SamplingContext`/`ContextualSampler`/`Action`/`Category`/`RateLimitSampler`, `TokenBucketRateLimiter`, `AdaptiveSampler`, `CompositeSampler`, `TriggerSink` |
| Crypto | `HashChainSink` + `TrailSigner`/`HmacTrailSigner`/`TrailVerifier`, `AesGcmCipher` + `EncryptionKeyProvider` — Integrity.kt, crypto/Encryption.kt |
| Sinks | Console, rotating `TextFileSink` (size/time/gzip/fsync/cipher/`ioDispatcher`), `CompositeLogSink`, Room target+sink, remote HTTP sink, WorkManager shipping, `LogcatSink`, bridges, OTel span sink |
| Room | `RoomLogEntity`/`RoomLogDao`/`RoomLogSchema`, `roomLogs()` one-liner, typed `RoomLogFilter/Page/Order`, `observe`/`count`/`distinctValues`, JSONL export/import, `RoomLogRetention*`, `RoomLogLegalHoldRegistry`, `RoomLogErasure`, `AesGcmRoomPayloadCipher`, `withAuditCallback` |
| Codegen | `@Log` (17 params) on interface members, open/abstract class members, top-level plain + extension functions, generics, `Result<T>`, `Flow<T>` lifecycle, `@LogConfig`, `@NoLog`, `@LogActions` registry, `META-INF/ilogger/schema.json`, `@LogDao` DB facade + Hilt/Koin modules, `autoChanges` diffing, 36 diagnostic codes (27 error / 9 warning), `ilogger.suppress` + `@Suppress` |
| Build | Gradle plugin `io.github.mohamed-dev-ui.ilogger` (11 DSL properties, `disabledVariants`, `iloggerDiagnostics`), BOM (13 constraints), Dokka, BCV dumps for 11 modules, Kover floors, reproducible archives |
| Tests | ≈730 `@Test` methods: core 346, cli 83, room 68, testing 63, otel 45, android 32, ktor 19, remote 19, jul 10, serialization 18, slf4j 8, ksp 4, gradle-plugin 5, examples 5, app 7 — plus 8-case `LogSinkConformance`, seeded property tests, negative KSP fixtures with verbatim assertions |
| Tooling | `ilogger-cli` (`tail/verify/export/schema/replay`, AES-GCM keyrings, exit codes), deterministic benchmark harness + `BudgetGateTest` (2 ns / 1.5 µs / 3 µs ceilings), 6 examples, Android reference app, 40 docs topics |

**Genuinely ahead of the competition** (preserve at all costs; no surveyed library
has these together): compile-time audit vocabulary with build-failing `Fix:`
diagnostics and a generated action schema manifest; first-class actor/entity/
outcome/correlation/causation on an immutable event; hash-chained + signed
tamper evidence with a standalone verifier CLI; AES-GCM at rest with key rotation
semantics; retention/legal-hold/erasure as library types; per-binding bounded
pipelines with drop reasons and delivery stats; compile-time DENY/MASK/HASH/OMIT
protection; deterministic TestKit with a reusable sink conformance suite;
audit-exempt rate limiting; a CLI that verifies evidence. Prior art surveyed:
Logback, Log4j 2, tinylog, SLF4J 2, kotlin-logging, Timber, Kermit, Logbook,
Envers / Spring Data auditing / Javers, Serilog, NLog, pino, winston, bunyan,
structlog, loguru, zap, zerolog, `log/slog`, tracing, swift-log (section 4).

**Competitor state at survey time (2026-11)** — the bar is moving, so these are
the versions each parity row below was measured against: Logback 1.6.6 (async
sifting, encoder/filter stack, **no JMX since 1.3.0**), Log4j 2 2.26.1 plus the
3.0.0-beta line, tinylog 2.8.1, SLF4J 2.0.20, kotlin-logging 8.0.4, Kermit 2.2.0,
Timber 5.0.1, Logbook 3.x, Spring Boot 4.1.2 (whose
`logging.structured.format.console=ecs|gelf|logstash` has made standards-shaped
console output a *default expectation*, not an add-on).

---

## 3. Weakness register

Evidence paths are relative to the repo root, verified at `5697555`. Line numbers
drift; treat them as anchors, not contracts.

### 3.1 Claims that do not hold (fix first — this is a credibility defect)

| ID | Claim | Reality | Evidence |
|---|---|---|---|
| D1 | "coverage targets met (core ≥ 90%)" (old G-12 acceptance) | Enforced Kover floor for core is **78** | build.gradle.kts:58-69 |
| D3 | Truncation "stops at depth 4" (docs/24:71) | Codec cap is `private const MAX_STRUCTURE_DEPTH = 2` | ilogger-serialization/.../JsonEventCodec.kt:308 |
| D4 | Redaction runs "after limits" (ILogger.kt:67-70, Redaction.kt:6-8) | Code redacts **before** limits; docs contradict the tested order | DefaultILogger.kt:640 vs 657 |
| D5 | G-01 "isolates sinks — a slow sink cannot delay another" | Write isolation is real; **admission, `isBacklogged`, `availableCapacity`, `stats`, `pendingCount` and the sampling-decision cache all take logger-wide locks** | DefaultILogger.kt:118,811,844,1725,1827,1841 |
| D6 | G-03 "every lost event is counted" | Dead-letter overflow only calls `reportError` (no counter, no `onDrop`); enricher and value-adapter failures are silent; `STRICT_REJECT` conflates limits+redaction+strict | DefaultILogger.kt:1545-1552,757; ValueAdapter.kt:59-63 |
| D7 | G-02 "flush is a barrier" | `FlushReport` cannot express sink-flush failure (`flushDistinctSinks` swallows throws → `timedOut=false`), and the `ILogger` interface *defaults* fake the barrier for third-party implementations | DefaultILogger.kt:1754-1764; ILogger.kt:732-749 |
| D8 | G-10 "a whole operation is sampled consistently" | `@Log(sampling=…)`'s `overrideRate` returns **before** the per-correlation decision cache, so START and SUCCESS can split | DefaultILogger.kt:790-794 |
| D9 | G-28 instrumented tests "cover lifecycle, persistence, JSONL, crash handoff and shipping" | One file, four cases; crash handoff and WorkManager shipping never run on a device | ilogger-android/src/androidTest/.../AndroidIntegrationTest.kt |
| D10 | G-42 "quality gates cover all modules" | `ilogger-remote`, `ilogger-otel`, `ilogger-slf4j`, `ilogger-jul` have **no named CI job** and are absent from the Windows/macOS matrix; docs/29 lists 7 API dumps and 6 floors while the tree has 11 and 10 | .github/workflows/ci.yml:62-87,129-140; docs/29-delivery-and-quality-gates.md:29-37 |
| D11 | KSP "supports every shape documented" | `@Log` on a `Flow` function with `dispatch = INLINE` or `onCancellation = LOG` is **falsely rejected** by an early check that ignores Flow, making the correct later checks dead code | ilogger-ksp/.../OpParser.kt:279-288 vs 347-355 |
| D12 | "9 diagnostics" | 9 of 36 codes have **no negative fixture** (ILG001, 004, 010, 011, 012, 015, 017, 018; ILG035 only tested suppressed); no fixture for `ilogger.strict` or `ilogger.enabled=false` | ksp-diagnostics*/src/main/kotlin/**/Fixtures.kt |
| D13 | `@LogSensitive(reason=…)` / `@LogExclude(reason=…)` | `reason` is never read by the processor although docs promise it in diagnostics | Protection.kt:46,69; zero occurrences under ilogger-ksp/src |
| D14 | "remote sink: engine-agnostic transports" | Only `JavaHttpTransport`, which is unusable on Android; the OkHttp adapter is a doc sketch | docs/33-remote-sink.md:14; RemoteTransport.kt:68-84 |
| D15 | "`minLevel` gates diagnostics only; `record()`/`recordNow()` (audit) are **never silently filtered**" (docs/16:187) while the same page recommends `DROP_OLDEST` as the application/mobile default | Overflow eviction is age-based and **level-blind**, so the documented recommended configuration *does* silently discard queued AUDIT events under flood — the promise holds for level filtering and is false for admission | docs/16-dispatch-backpressure-error-modes.md:51,154,187; docs/21-android-app-guide.md:225; DefaultILogger.kt:496,849,886 (see E15) |

### 3.2 Engine, concurrency and delivery (E)

| ID | Weakness | Impact | Evidence |
|---|---|---|---|
| E1 | `enabled` is a plain non-volatile `var`; `ILoggerConfig` is mutable after build (`ValueAdapterRegistry.register` is public, unsynchronized `HashMap`) | Cross-thread level toggles have no visibility guarantee; concurrent registration can corrupt state | DefaultILogger.kt:93; ValueAdapter.kt:37-49 |
| E2 | Global `admissionLock` serializes every binding's offer/poll/stats; a second global lock guards sampling decisions | Contention ceiling under multi-thread producers; contradicts per-binding isolation | DefaultILogger.kt:118,811 |
| E3 | Memory ceiling is **count-based** (`bufferSize` events) with no byte budget, and `limits` defaults to `null` | N bindings × 4096 arbitrarily large events = OOM-shaped risk | ILogger.kt:118,198 |
| E4 | Whole-batch retry (`writeBatch`) re-sends everything after a partial failure; no per-event acknowledgement | Duplicates at every sink that is not idempotent; docs even warn `FAIL_FAST`+`RETRY` can duplicate | DefaultILogger.kt:1463-1488 |
| E5 | `TextFileSink` documents single-process writers; `HashChainSink` restarts `seq` at 0 per instance, and structural verification accepts it | Two live loggers into one store intermix chains and verification still passes | TextFileSink.kt:41; Integrity.kt:89-90,205-217 |
| E6 | Decorators re-introduce serialization: `HashChainSink` holds a coroutine `Mutex` across the delegate write; console/file hold a monitor per write | Chained audit trails are single-threaded end to end | Integrity.kt:92-112 |
| E7 | No dedupe/log-once; `suppressSources` is exact-match though documented as prefix matching | Repeated identical events flood sinks; suppression is brittle | LogContext.kt:55-60; DefaultILogger.kt:626 |
| E8 | Hot path allocates: `EventBuilder` instance, 2 metadata/changes copies per event, batch list re-copy, `routesFor` list, `DroppedEvent` list per admission, `EnumMap` snapshot per `stats()`, `Pair` vararg arrays per diagnostic, per-call `.validated()` wrapper | Undermines the sub-µs producer claim under real load | DefaultILogger.kt:595-620,1385,1717,842; Codegen.kt:652-695 |
| E9 | `PROPAGATE` silently degrades to RETRY-then-DROP on async paths | Callers cannot rely on "propagate" semantics they explicitly configured | DefaultILogger.kt:1447-1449; Support.kt:114-119 |
| E10 | `isLoggable` can consume a token-bucket permit that the later `record` does not reuse | Double-charged rate limits, phantom shed volume | DefaultILogger.kt:98-111,809 |
| E11 | Ids are UUID-v4 only (ThreadLocalRandom); no ULID/monotonic/time-ordered id, no persisted sequence per stream | Cannot prove "no gaps"; ordering across producers is guesswork | Support.kt:42-49 |
| E12 | `timestampMillis` only, ms precision, no occurred-vs-observed pair, no timezone/calendar model, no clock-provenance record | OTLP/ECS mappings lose `observed_time_unix_nano`; auditors discount timestamps with no clock story | LogEvent.kt; docs/30 |
| E13 | Redaction and limits only see `String` values in metadata/changes/actor; `LogFailure.message`, `action`, `entity`, `source`, `category`, `actor.id` are never redacted; unknown types are `toString()`-ed under TRUNCATE | Secrets of numeric/other type escape; the top GDPR/PCI leak vector | Redaction.kt:307-346; EventLimits.kt:501-509 |
| E14 | `StrictValidation` materializes `Iterable`/`Array` with `.toList()` before counting (unbounded), checks actor attributes against `maxEntries` instead of `maxActorAttributes`, and never validates the failure payload | The guard rail itself can OOM; inconsistent enforcement | Strict.kt:79,195-208 |
| E15 | **Overflow eviction is level-blind**: `offer(event, dropOldestWhenFull = …)` evicts the *oldest* queued event whatever its level or outcome, and there is no discarding-threshold analogue. `BLOCK` has a timeout on the blocking entry points but the async path has no never-block option | A DEBUG/TRACE burst silently evicts queued **AUDIT** events — the exact opposite of the product's promise, and Logback's `discardingThreshold` exists precisely to prevent it | DefaultILogger.kt:496,849,886; BindingPipeline offer path |
| E16 | No garbage-free/reusable-record mode: every diagnostic builds a `String` + `Pair[]` + `EventBuilder` + event copy, so throughput is allocator-bound rather than lock-bound | Log4j2 documents ≈ 809 MB/s of GC garbage per second on a normal async appender vs ≈ 1.6 MB/s when garbage-free; ILogger reaches its 1.5 µs admission ceiling (~950 ns measured, ≈ 1 M events/s/producer) *by* allocating, so sustained multi-producer load is allocator-limited | E8 above; `Budgets.ASYNC_ENQUEUE_NANOS` at benchmark/src/main/kotlin/benchmark/Budgets.kt:25 |
| E17 | No shared/multi-process writer coordination: two processes appending to the same file (or the same Room DB) have no lock, sequence or rotation ownership protocol — tinylog ships a shared writer as a first-class mode | Rotation and chain state corrupt silently in multi-process Android/serverless deployments | E5; TextFileSink.kt:41 |

### 3.3 Facade and event model (A)

| ID | Missing capability | Prior art | Evidence |
|---|---|---|---|
| A1 | **No named loggers, no hierarchy, no child/scoped loggers.** No `name`, no `getLogger(name)`, no `logger.child(...)` | Logback/Log4j2 logger tree, pino `child()`, slog `With`, zerolog `Ctx`, loguru `bind` | ILogger.kt:781-814 |
| A2 | **No runtime reconfiguration.** No way to change `minLevel`, add/remove a sink, or reload config on a live logger; only `enabled` | Spring Boot `/actuator/loggers`, log4j2 JMX + `monitorInterval`, Serilog `ReloadableLogger`, zap `AtomicLevel`, NLog `autoReload`, tracing `EnvFilter` reload | ILoggerConfig never surfaced; DefaultILogger.kt:51-53 |
| A3 | Diagnostics are eager `String` + `vararg Pair`: no lazy `{ }` suppliers, no `{}`/`%s` parameterized formatting, no `Throwable` overloads, no `isTraceEnabled(Marker)` | SLF4J/Log4j2 parameterized API, log4j2 lambda suppliers, `slog.LogValuer`, `zap.Check()` | ILogger.kt:403-407 |
| A4 | **No markers/tags and no `markers`/`tags` field on the event**; no TurboFilter-style pre-construction veto; no filter that can *rewrite* the level, and no matcher/evaluator filter objects composable in config | Logback Markers + `TurboFilter` (which may also change the level and the message) + `EvaluatorFilter`/`MarkerFilter`/`LevelFilter`, log4j2 Marker + `LevelRangeFilter`, NLog `when` conditions | LogEvent.kt:39-85 |
| A5 | **Ambient attributes never reach events**: `LogContext.attributes` is not merged by the engine, is `Map<String,String>` only, has no NDC-style stack, no auto-inheritance to worker threads, and no declared `ThreadLocalAccessor`/`MDCContext` interop contract (copy-on-enter vs clear-on-exit semantics are exactly the open question kotlinx.coroutines #4187 leaves to the library) | SLF4J MDC + `kotlinx-coroutines-slf4j` `MDCContext(copyOnEnter=, clearOnExit=)`, Micrometer `context-propagation` `ThreadLocalAccessor`, log4j2 `ThreadContext` + `ContextDataStorage`, Serilog `LogContext.PushProperty` | `attributes` has zero occurrences in DefaultILogger.kt; LogContext.kt:52 |
| A6 | No caller location (class/method/file/line), no opt-in stack walker | Logback caller data, log4j2 `LocationInfo` (expensive, documented), swift-log passes file/line as data | no such field on `LogEvent` |
| A7 | No per-sink level/predicate: `SinkBinding` is `(sink, category)` and routing is category equality; `dispatch`, `batchSize`, `retry*`, `ErrorMode` are logger-global | Serilog `MinimumLevel` per sink + `Filter.With`, NLog `when`, pino multistream level routing, tracing per-layer filters | ILogger.kt:17; DefaultILogger.kt:1715-1723 |
| A8 | No filter/layout SPI separation, no decorator library (retry/batch/filter/dedupe/circuit-breaker/ring-buffer), no sifting/dynamic child sink | Logback `Filter`/`Layout`/`SiftingAppender`, NLog wrappers, tracing layers | only `Composite/HashChain/Trigger` exist |
| A9 | No fixed order of stages the user controls: enrich → adapters → suppress → redact → limits → sample → strict is hard-wired | structlog processor pipeline, pino transport pipeline | DefaultILogger.kt:613-741 |
| A10 | No `FATAL`/`NOTICE`/`DPANIC`-class levels, no level **aliases**, no user-defined levels, no level-offset mapping for a bridged legacy stack, no OTel severity-number mapping | log4j2 `CustomLevel`/`LevelOffset`/`SimpleLevelRangeFilter`, swift-log, slog, zap, pino, OTel 1-24 | LogLevel.kt:14-22 |
| A11 | No duration/profile events, no `auditSpan {}` scope with inherited fields and terminal duration, no exception cause-chain beyond an 8-frame summary | tracing spans + `with_span_events`, SerilogTracing, winston `profile`, structlog `dict_tracebacks` | EventDetails.kt:55-80 |
| A12 | No guaranteed-fail audit binding ("if it cannot be recorded, the transaction must not commit") with a typed exception; no self-log for failures of the logging machinery itself (including the dead-letter path) | Serilog `AuditTo`, zap metrics hooks, log4j2 `StatusLogger`, pino transport errors | ErrorMode/`onDispatchError` only |
| A13 | No ring-buffer/in-memory tail for in-app or CLI live inspection of a running process; no process-exit hook (`pino.final`) | bunyan `RingBuffer`, pino.final | docs/36 |
| A14 | `ILogger` interface defaults silently break guarantees for custom implementations (`hasSinks` ignores bindings, `flush(timeout)` returns a zero report, `awaitIdle` returns true, `recordBlocking` ignores its timeout, `tryRecord` returns true) | — | ILogger.kt:588-749 |
| A15 | 13-14 positional parameters per entry point; adding a field is source-breaking; `DisabledILogger` duplicates the surface | — | ILogger.kt:424-564 |
| A16 | **No effective-config introspection as data**: a live logger cannot answer "what are my bindings, levels, filters, stage order and budgets right now?" (`stats()` reports counters only) — so an incident responder has to read source or guess | Logback `LoggerContext`/`JMXConfigurator` view, log4j2 `Configuration` + `status` command, NLog `LoggingConfiguration` reload/view | ILogger.kt:781-814; Delivery.kt |

### 3.4 Codegen and build (C)

| ID | Weakness | Impact | Evidence |
|---|---|---|---|
| C1 | `@Log` targets FUNCTION only; no properties, constructors, object/companion/enum/sealed members | Many real audit points (state transitions, lifecycle callbacks) stay manual | Log.kt:114; ILoggerProcessorProvider.kt:164-224 |
| C2 | No check for `private`/`local`/`inline`/`operator`/`expect`/`actual` members → generated code that cannot compile instead of a diagnostic | Broken builds, unhelpful errors | ILoggerProcessorProvider.kt:179-190; Codegen.kt:270-274 |
| C3 | Interface wrappers are `internal` while `logged()` is always public → type-exposure errors in explicit-API modules; class wrappers are `internal` with **no** `logged()`, so they cannot be used cross-module | The open/abstract-class feature is effectively single-module | Codegen.kt:101-143,176-210 |
| C4 | Zero UI/stream breadth: no `@Composable`, `@Preview`, View/Fragment lifecycle, Rx `Publisher`; `StateFlow`/`SharedFlow`/`Channel`/`Sequence` are not recognized (prefix match on `kotlinx.coroutines.flow.Flow`) | Compose and modern repository APIs cannot be instrumented declaratively | Ks.kt:199-200 |
| C5 | Java-declared and cross-module `@Log` sites are silently skipped (`containingFile == null`) | Silent under-instrumentation in mixed builds | ILoggerProcessorProvider.kt:181,194,382,417 |
| C6 | Incremental dependency set omits the `entity = X::class` file, the `@LogConfig` file, extractor/adapter files and `disabled`/`strict` option changes; no test asserts UP-TO-DATE; no round bound on deferred symbols | Stale generated code and wrong builds; correctness unproven | ILoggerProcessorProvider.kt:561-577,525-536 |
| C7 | `schema.json` is per-module and aggregating only within it; the CLI reads one manifest — no cross-module aggregation or registry | Action-vocabulary collisions across modules go unnoticed | ILoggerProcessorProvider.kt:748-764; SchemaCommand.kt:29 |
| C8 | All annotations are `@Retention(BINARY)` → no runtime introspection or compiled metadata export | Schema tooling cannot read a jar | Log.kt:115 |
| C9 | Per-call allocations in generated wrappers (`mapOf` metadata, `listOfNotNull` changes, `.validated()` per event, `toString()`/hash inline) | Audit path cost scales with metadata count | Codegen.kt:652-695; LogValueExtractor.kt:33-38 |
| C10 | No compile-time overhead or generated-code size measurement (JMH covers runtime only) | Build-time regressions invisible | benchmark/build.gradle.kts:5,22 |
| C11 | Suppression is footgun-shaped: `"ALL"`/`"ILG"` silence the whole catalog in scope; `expect-diagnostics` downgrades fatal errors | A misconfigured module can silently green a build | ILoggerProcessorProvider.kt:137,896-901 |
| C12 | No IDE support: `Fix:` text lives in the message; no inspections/quick-fixes/intention | Slower adoption in the IDE | Diagnostic.kt |
| C13 | Extractors can be *required* from the registry but are never generated into Hilt/Koin graphs; DI support exists only for DAOs | Manual wiring for every extractor | Codegen.kt:331-336; HiltBindings.kt:56-66 |
| C14 | Gradle plugin: JVM/Android only, silently does nothing for KMP or when no Kotlin plugin is applied; adds `implementation` not `api`; no test that dependencies are actually added, no test that `disabledVariants` stops generation in a real build; no version-mismatch check; outside `apiCheck`/Kover (separate included build) | Setup that looks applied but isn't | ILoggerPlugin.kt:24-101; ILoggerPluginTest.kt:67-138 |
| C15 | **Minification is unproven and partly wrong**: `consumer-rules.pro` carries a single `-dontwarn androidx.work.**`, no `-keep`/`-keepnames` guidance ships for generated adapters or `@LogActions` constants, and CI only builds `assembleDebug`. Worse, `LogFailure` records `error::class.java.name` / `rootCauseType`, which R8 renames in a minified release build — so failure audits silently become unreadable (`ClassNotFoundException`-style tokens instead of exception names) | Release Android builds are the flagship audit target; today the failure trail breaks exactly where it matters most | ilogger-android/consumer-rules.pro:1-6; EventDetails.kt:70-72; ci.yml:162 |

### 3.5 Platform and integration (I)

| ID | Weakness | Impact | Evidence |
|---|---|---|---|
| I1 | No non-JVM targets anywhere, even in the dependency-free modules (serialization, testing, annotations); `ThreadLocal`, `UUID`, `MessageDigest`, JVM atomics block it | iOS/JS/Wasm/native apps cannot use the library at all | docs/37-kmp.md:11-27 |
| I2 | `ilogger-room` is `kotlin.jvm`, so Room 2.8.5 KMP support is unused; indices exist only on `ts`/`level`/`action` while filters query `actorId`/`entityId`/`correlationId`; public paging is `LIMIT/OFFSET` (keyset exists only privately); `RoomLogFilter` cannot filter `source`/`category` though they are facets; `TABLE_VERSION = 1` with a create-only migration helper; no `PagingSource`, FTS, WAL tuning, multi-process story | Audit viewers do full scans; export is O(offset); schema has no proven v2 path | RoomLogEntity.kt:21; RoomLogQueries.kt:32-45,54-58,162-166; RoomLogExport.kt:48-69 |
| I3 | `ilogger-remote`: one `Mutex` serializes writes, drains, retries **and backoff sleeps**; `FileRemoteSpool.removeFirst` rewrites the whole file per ack (quadratic on 64 MiB); static batch size; single global circuit breaker with no half-open probe cap; auth is a header lambda (no client-credentials flow, no 401-refresh-replay); `JavaHttpTransport` unusable on Android | Throughput ceiling, disk-write amplification, no Android story | RemoteLogSink.kt:127,139,172,200,354-376; RemoteSpool.kt:107-124 |
| I4 | `ilogger-serialization`: single text codec, `EventCodec` SPI is **String-only** (binary formats require String→UTF-8 re-encoding), depth guard 2 and private, opaque `IllegalArgumentException` on unknown enum text, `cancelled` failures documented lossy, no streaming `decodeAll`, no typed `CodecException` | Cannot produce OTLP/protobuf/CBOR/CLEF bytes cleanly; decode errors unusable | JsonEventCodec.kt:144-146,171,191,308; RemoteLogSink.kt:310 |
| I5 | Bridges are programmatic only: `ILoggerAppender` and `JulLogHandler` take the logger as a constructor argument, so **neither can be declared in `logback.xml` / `logging.properties`**; no `LocationAware`, no `TurboFilter`, no Marker forwarding, no Log4j2 appender; outbound path writes no MDC | "Drop-in legacy adoption" (docs/39) is weaker than advertised | ILoggerAppender.kt:42-113; JulLogHandler.kt:38-45 |
| I6 | `ilogger-otel`: **no OTLP log exporter** (events become spans, not `LogRecord`s), no severity-number/body/`event.name` mapping, no `opentelemetry-sdk-logs` even as a test dependency; `withRemoteParent` uses `Span.makeCurrent()` inside a `suspend` block → context is lost across suspension/`withContext`; no exemplars; unbounded `category` label cardinality in metrics | The stated trace-correlation story is partial and coroutine-unsafe | OtelTrace.kt:94-104; OtelSpanLogSink.kt:56,139; docs/35:182 |
| I7 | `ilogger-ktor`: metadata is only method/path/status/duration (no body or header capture policies, no bounded preview, no allow/deny lists); `path` (not route template) is the dimension → cardinality explosion; default actor trusts unauthenticated `X-User-Id`; `CallFailed` omits duration; clock is `SystemClock`, not the configured one; no WebSocket/SSE; JVM-only client. Nothing approaches Logbook's request-audit contract (`body-only-if-status-at-least`, pluggable correlation strategies + `Correlation`, header/query/path/body filters with declarative predicates, `Strategy`, `Compliance` policy, uniform obfuscation) | Security-relevant blind spot; unbounded series; testability | HttpAudit.kt:97-99,192-197,224-228 |
| I8 | `ilogger-android`: `ILoggerAndroid.install` is used by nothing (the app wires manually), so the flagship one-liner has no consumer coverage; no ANR/`ApplicationExitInfo`/`onTrimMemory` ingestion; `CrashHandoff` report directory is unbounded; `ShippingSpool.appendAll` has **no size cap**; transport is a process-wide `@Volatile` static `configure` (untestable, wrong multi-process); only `NetworkType.CONNECTED` constraints, no periodic work, no user consent gate; consumer ProGuard rules are one `-dontwarn`; CI builds `assembleDebug` only — release/R8 and APK size are never exercised; no library-owned on-device viewer | Android production readiness gaps | ILoggerAndroid.kt:40-152; CrashHandoff.kt:90-95; ShippingSpool.kt:53-61; LogShippingWorker.kt:56-66,81-85; ci.yml:162 |
| I9 | `ilogger-testing`: conformance suite is an **abstract JUnit 4 class** (JUnit 4 is an `api` dependency) — unusable as a JUnit 5 `@RegisterExtension`/`@TestFactory`; no Kotest/Truth/Turbine/MockK story; no fakes for `RemoteTransport`/`ShippingTransport`/`RoomLogDao` (each module hand-rolls one); `awaitCount` still polls real time | Test ecosystem mismatch for 2026 Kotlin projects; Room `observe` is untestable with shipped fixtures | LogSinkConformance.kt:52-191; ilogger-testing/build.gradle.kts:8-9 |
| I10 | BOM excludes third-party constraints and nothing verifies "every module is in the BOM"; the plugin injects hardcoded coordinates | `ilogger-room` (Room 2.8.5) pairs silently with an incompatible Room/Ktor/OTel | ilogger-bom/build.gradle.kts:15-27 |
| I11 | Examples demonstrate 6 of ~20 capability areas; remote shipping, OTel, bridges, integrity/encryption end-to-end, CLI and `install()` are not shown; `:app` release variant untested | Docs claim parity the demo app does not prove | ci.yml:78-83 |
| I12 | **No entity revision history read-model.** The store keeps *events* (some with `FieldChange`s) but offers no "state of entity X as of time T", no per-entity revision list, no diff-between-revisions query — the question every auditor asks second after "who did it" | Envers `AuditedReader` revision queries, Spring Data auditing `@CreatedDate/@LastModifiedBy` + revision history, Javers `findByGlobalIdAsList`/snapshot diff | RoomLogQueries.kt (no as-of API); FieldChange is write-side only |
| I13 | **No platform-writer layer to adopt on native targets**: nothing comparable to Kermit's composable `LogWriter` list, per-writer `minSeverity`, `MessageSplitter`, context tags, or crash-reporter adapters (Crashlytics/Sentry/Bugsnag), and `LogcatSink` clamps line length (3 800) but not tag length — pre-API 26 truncates tags at 23 chars, breaking tag-based Logcat filters | Kermit 2.2.0, swift-log `LogHandler`, Timber `Tree` | LogcatSink.kt:49-67 |
| I14 | **No in-app inspection story at all** while the Android ecosystem expects one: Meta retired Flipper, and the maintained tools (Lens, Chucker, DbInspector) prove demand for zero-ADB on-device viewing — ILogger has a CLI that can read the Room store but nothing on-device | lokal.ai Lens, Chucker, DbInspector; bunyan/pino `RingBuffer` in-app tail | docs/36; no viewer artifact |

### 3.6 Operations, standards and compliance (O)

| ID | Gap | Why it matters |
|---|---|---|
| O1 | No standards-shaped output: everything is bespoke JSONL. No OTLP logs (protobuf/HTTP), no ECS, no CLEF, no GELF, no RFC 5424, no Loki push, no EMF/GCP payload shapes, and **no template-driven field mapping** (include/exclude/rename/add per field, the way `JsonTemplateLayout` and Spring Boot's `logging.structured.json.*` let a team reshape output without code) | Every backend today is a custom adapter written by the consumer; blocks adoption in ELK/Loki/Seq/Graylog/Datadog estates |
| O2 | No admin surface: nothing at runtime can read or change levels, add a sink, or drain — no HTTP endpoint, no config source (file/HTTP/Consul/etcd), no TTL-bounded level grants, no effective-config view (A16). Note the security precedent: **Logback removed its JMX configurator in 1.3.0** because unauthenticated MBean access is a remote-code-execution surface — so JMX must be optional and hardened, never the primary path | Incident response requires a redeploy; competitors treat this as table stakes (with the caveat below) |
| O3 | No self-diagnostics channel (status log) for failures of the logging machinery itself, including dead-letter and redaction failures | "Logging is broken" is invisible exactly when you need it |
| O4 | No health/SLI surface shaped like `LoggingHealth` (oldest-in-queue age, per-binding state, sink last-success, drop rate) beyond `stats()` counters | Cannot alert on audit loss; no readiness tie-in |
| O5 | Shutdown: two-phase drain, JVM hook, Kubernetes `preStop`, Android-safe flush are not a documented contract with tests | Tail truncation in containers and ANR risk on mobile |
| O6 | No gap-free sequencing: no persisted monotonic per-stream sequence, no restart continuity, no *loss marker records inside the chain* | PCI DSS Req. 10 / examiner question "how do you know nothing is missing?" is unanswered |
| O7 | Integrity is chain-only: no Merkle roots, no periodic anchoring, no RFC 3161 trusted timestamps, no key custody SPI (HSM/KMS, non-extractable write key), no separation of duties, no per-generation key id in every record | A chain proves edit-detection, not non-repudiation; ops can still rewrite history |
| O8 | No evidence export spec: no signed portable manifest/verifier bundle that a third party can parse without the app's source | Audits stall at "send me something I can verify independently" |
| O9 | Compliance state is in memory (legal holds) with no persistence contract; erasure emits one event but no signed deletion certificate; holds do not survive config rollback | GDPR Art. 17/30 and legal hold need durable, provable state |
| O10 | "Audit the audit" is missing: log reads, exports, queries, level changes and purges are not themselves audit events; truncation policy disclosure exists but is not surfaced in evidence exports | Standard examiner requirement, almost never implemented |
| O11 | No tenant routing / data-residency fail-closed, no per-tenant keys/retention/quotas, no field-level PII classification carried to the wire, no DLP detector packs (gitleaks/truffleHog-compatible) | Multi-tenant SaaS and residency compliance need it; silent cross-region writes are violations |
| O12 | Clock provenance is undocumented: no NTP/monotonic story, no event on backward jumps, no max-observed-offset | Timestamps are the primary audit dimension |
| O13 | Supply chain: no SBOM, no SLSA provenance/attestations, no cosign/Sigstore signing, no OpenSSF Scorecard run, no dependency-CVE/license gate | Enterprise procurement asks first |
| O14 | Tooling: CLI ships only as `installDist` (no fat jar/native image/Windows `.exe`), no config file/env defaults, no shell completion, no `redact`/`hold`/`purge`/`diff`/arbitrary SQL/JSON-Schema generation; no TUI/Compose/IntelliJ viewer; SQLCipher-backed DBs unsupported | Evidence work happens in tools people already have |
| O15 | **Destination breadth is HTTP-JSON only.** The incumbents win on the *pair* (transport × format), not the sink: log4j2/Logback ship 20-40 appenders (Kafka, AMQP/JMS, JDBC batch, SMTP, syslog, Socket, Failover/Rewrite/Additivity wrappers, CloudWatch, loki4j, logstash). ILogger has one transport (HTTP) and one format (bespoke JSONL), so each ecosystem target is a consumer project | Adoption in an estate that is not "POST JSON to us" is blocked; the right unit of work is transport SPI × encoder SPI, not new sinks |

### 3.7 Testing and quality gates (T)

| ID | Gap | Evidence |
|---|---|---|
| T1 | Coverage floors are below the roadmap's own acceptance bar (core 78, room 82, remote 82, cli 80) and are line-only — no branch or mutation gate | build.gradle.kts:58-69 |
| T2 | Confirmed untested paths: `enqueueBlockingSync` wait/`InterruptedException` branch; `sinkConcurrency > 1` `awaitFullyIdle` barrier (only one test sets 3); `AdaptiveSampler.minRate` clamp; `DefaultTextEventCodec` field rendering; `HashChainSink` with a throwing key provider; `AesGcmCipher` unknown-key-id decrypt under concurrency; `TriggerSink.flush/close`; `LogSafeValue` × redaction interaction; `FixedClock` visibility; post-build `ValueAdapterRegistry.register` thread-safety; `maxTotalBytes` smaller than the active file; concurrent `enabled` flips | DefaultILogger.kt:989-1042,1285-1305; test tree |
| T3 | Conformance suite adopted by only 4 sinks (`InMemory`, `Fake`, `TextFileSink`, `RoomLogTarget`); `ConsoleLogSink`, `CompositeLogSink`, `TriggerSink`, `HashChainSink`, `LogcatSink`, `AndroidFileSink`, `RemoteLogSink`, bridge sinks are not conformance-checked | TextFileSinkConformanceTest.kt:11 |
| T4 | 4 integration modules have exactly one test file each and no named CI job; nothing runs the CLI distribution; no OS matrix for remote/otel/slf4j/jul | §3.1 D10 |
| T5 | No fuzz/property tests for the value grammar, redaction rules, limits, KSP parser, or the Room query builder; property tests cover only 2 areas | JsonEventCodecPropertyTest.kt, RoomLogImportPropertyTest.kt |
| T6 | No performance gate for codec, Room query/export, remote drain, hash chain, Android paths, or KSP compile-time overhead | benchmark/ |
| T7 | No concurrency model checking / state-space exploration for the engine (deadlock, lost wakeup, torn watermark), and no long-run soak or leak test | — |
| T8 | Docs and claims are not machine-verified (D1-D15 all slipped through): no test that the support matrix, constants table, annotation reference or diagnostics catalog match the code | docs/24, docs/38 |

---

## 4. Parity matrix (prior art → target)

Level of ambition per row: **P0** = required to be called professional-grade,
**P1** = required to be called complete, **P2** = differentiator.

| Capability | Prior art | ILogger today | Phase / goal |
|---|---|---|---|
| Named loggers + hierarchy + inherit-from-parent config | Logback, Log4j2, JUL, NLog | none | 2 / G-56 |
| Child/scoped loggers with bound fields | pino `child`, slog `With`, zerolog `Ctx`, loguru `bind` | none | 2 / G-56 |
| Runtime level change (+TTL), sink add/remove, attach/detach a backend | Actuator `/loggers`, log4j2 JMX, Serilog reloadable, zap `AtomicLevel`, NLog autoReload, Timber `plant`/`uproot` | `enabled` only | 2 / G-57, 6 / G-86 |
| Effective-config introspection as data | Logback `LoggerContext`, log4j2 `Configuration` + `status` | counters only | 6 / G-86, G-88 |
| Lazy messages, parameterized formatting, throwable overloads | SLF4J, log4j2, tinylog | eager strings | 2 / G-58 |
| Custom levels, aliases, level-offset mapping, range filters | log4j2 `CustomLevels`, `LevelOffset`, `LevelRangeFilter` | 6 fixed levels | 2 / G-58 |
| Markers/tags + pre-construction veto (TurboFilter, incl. level-rewriting) | Logback Markers + `TurboFilter`, log4j2 Marker | none | 2 / G-59 |
| Matcher/evaluator filter objects composable per binding | Logback `EvaluatorFilter`/`MarkerFilter`/`LevelFilter`, NLog `when` | category equality only | 2 / G-61 |
| MDC/NDC parity incl. coroutine `MDCContext` / `ThreadLocalAccessor` | SLF4J MDC + `kotlinx-coroutines-slf4j`, log4j2 ThreadContext, Micrometer context-propagation | attributes not merged | 2 / G-60 |
| Per-sink level range + predicate routing | Serilog restricted sinks, NLog `when`, tracing layers, pino multistream | category equality | 2 / G-61 |
| User-ordered processor pipeline | structlog, pino transport pipeline | fixed stages | 2 / G-61 |
| Sifting/dynamic child sinks (discriminator + idle timeout + max children) | Logback `SiftingAppender`, `AsyncAppender` sifting variant | none | 2 / G-61 |
| Caller location as opt-in data | swift-log (file/line as data), logback caller data (slow) | none | 2 / G-59 |
| Layout/encoder separated from sink + template-driven field mapping | Logback Layout/Encoder, log4j2 `JsonTemplateLayout`, Spring Boot `logging.structured.json.include/rename/add` | `EventCodec` (String only) | 5 / G-80 |
| **Level-aware shedding on overflow** (never evict AUDIT for TRACE) | Logback `AsyncAppender` `discardingThreshold`/`neverBlock`/`maxFlushTime`, NLog overflow actions | level-blind `DROP_OLDEST` | 1 / G-50 |
| Ring-buffer sink + guaranteed final flush | bunyan RingBuffer, pino.final | none | 1 / G-54, 6 / G-89 |
| Audit binding that fails the caller on delivery failure | Serilog `AuditTo`, NLog `Block` | ErrorMode knobs | 7 / G-96 |
| Self-log for logging's own failures | log4j2 StatusLogger, Serilog SelfLog | `onDispatchError` | 6 / G-87 |
| Async throughput class + garbage-free allocation | log4j2 Disruptor + garbage-free mode, Logback async | coroutines + channels, global locks, allocating hot path | 1 / G-50, G-55 |
| Rolling by time+size+cron with name patterns and policy set | log4j2 `Policies`, loguru `rotation`, tinylog `policies=daily\|monthly\|size\|startup\|dynamic` | size+age+gzip present, no name pattern | 1 / G-53 |
| Shared multi-process log writer | tinylog shared `Writer` | single-process documented | 1 / G-53 |
| Compose/Android lifecycle instrumentation | none of the JVM libs; Timber trees | none | 3 / G-68 |
| R8/ProGuard consumer rules shipped **and** proven minified | every maintained Android library ships keeps | one `-dontwarn`; failure type names get obfuscated | 3 / G-68, 4 / G-78 |
| Reactive/stream targets beyond cold `Flow` | tracing, vert.x, Rx | cold Flow only | 3 / G-66 |
| IDE inspections + quick fixes | IntelliJ Kotlin plugin ecosystem | message-embedded `Fix:` | 3 / G-72 |
| KMP targets (iOS/JS/Wasm/Native) | Kermit, swift-log, kotlinx-based libs | JVM+Android | 4 / G-76 |
| Composable per-target writers + crash-reporter adapters | Kermit `LogWriter` list, `minSeverity`, interceptors, Crashlytics/Sentry/Bugsnag adapters, iOS `os_log`, JS `console` | `LogcatSink` + crash handoff only | 4 / G-76, G-79 |
| On-device zero-ADB inspector | Lens, Chucker, DbInspector (Flipper retired by Meta) | none | 4 / G-78 |
| Entity revision history / "state of X as of date T" | Hibernate Envers, Spring Data auditing, Javers | change events, no read-model | 5 / G-111 |
| HTTP request/response audit contract (bodies, filters, correlation, compliance) | Zalando Logbook | method/path/status metadata only | 5 / G-112 |
| OTLP logs exporter | OTel SDK, log4j2/Logback appenders | none | 5 / G-81 |
| ECS / CLEF / GELF / RFC 5424 / logfmt encoders | Elastic ECS, Seq CLEF, Graylog, syslog | none | 5 / G-80 |
| Loki push with label vs structured-metadata policy | Grafana Alloy/OTel collectors, loki4j | none | 5 / G-82 |
| Destination breadth as transport×encoder **pairs** (Kafka, AMQP/JMS, JDBC batch, SMTP, syslog, ES, EMF) | log4j2 + Logback appender catalogues (20-40 appenders) | HTTP + bespoke JSONL only | 5 / G-82, G-83 |
| Out-of-process/sidecar transport | pino worker transports, loguru `enqueue` | in-process workers | 5 / G-85 |
| DLP/secret detector packs + format-preserving masking | structlog `CardMasker`, gitleaks/truffleHog | fixed regex list | 7 / G-94 |
| Tenant routing + residency fail-closed | enterprise collectors | none | 7 / G-98 |
| Merkle anchoring + RFC 3161 + custody/HSM | examiner practice, SIEMs | chain only | 6 / G-91 |
| Deletion certificates + persistent holds | GDPR tooling | in-memory holds | 6 / G-93 |
| Admin + health + preStop draining (JMX optional, hardened) | log4j2 JMX, Actuator, K8s — and Logback's **removal** of JMX in 1.3.0 | none | 6 / G-86-89 |
| JUnit 5 extension, Kotest/Truth/Turbine fixtures | every modern Kotlin test lib | JUnit 4 abstract class | 8 / G-99 |
| Mutation testing gate | PIT (standard on JVM) | none | 8 / G-102 |
| SBOM/SLSA provenance/Scorecard | OSS supply chain | reproducible builds only | 9 / G-109 |
| Native/fast CLI distribution, TUI viewer | lnav, `hl`, Gonzo | `installDist` | 9 / G-106-108 |

---

## 5. Testing doctrine ("100% of possible cases", made operational)

"100% of possible cases" cannot mean infinite input; it means **every declared case
in the feature's state matrix, and every state matrix for the subsystem**. Each
goal below carries a `Test gate` naming its matrix; this section is the rule set
that makes those gates meaningful.

1. **Model first.** Each capability is described by an explicit state model in
   `docs/xx`: inputs × configuration × platform × failure mode × concurrency. The
   matrix is written down before tests, and the roadmap item lists its size.
2. **Full Cartesian below 512 cases.** Anything the model produces at ≤ 512
   combinations is enumerated exhaustively — no sampling. Above 512: **t-wise
   (t = 3) covering arrays** plus targeted full sweeps of the axes with known
   interactions (dispatch × overflow × error mode, level × sampling × redaction).
3. **Boundary law.** Every numeric limit (`bufferSize`, `batchSize`, timeouts,
   `EventLimits.*`, retention windows, rate bursts, key sizes, chunk sizes) gets
   tests at `0, 1, n-1, n, n+1, max, overflow` and at the documented default.
   A missing boundary case blocks the phase.
4. **Failure-path parity.** Each public behavior needs its failure twin: throwing
   sink, throwing enricher/redactor/adapter/extractor/key provider, full queue,
   closed logger, canceled scope, disk full, `IOException`, HTTP 408/429/5xx,
   corrupted line, unknown enum text, absent key. Success-only coverage is
   declared insufficient.
5. **Concurrency law.** Every shared structure is proven under stress with a
   deterministic harness: JCStress-style litmus tests for visibility (`enabled`,
   watermarks, config), `kotlinx-coroutines-test` virtual time for timing,
   `-XexitAtStateTimeout` soak runs (30 min) for leak/deadlock, and
   `awaitFullyIdle` checks at `sinkConcurrency ∈ {1, 2, 4, 8}`.
6. **Property + fuzz.** Codecs, redaction, limits, KSP grammar, query builders and
   Room paging get seeded property tests with adversarial generators (quotes,
   escapes, control chars, Unicode, cycles, 10⁴-depth, huge collections, empty,
   NaN/Infinity, locale-varying `toString`), plus a JVM fuzz round per release.
7. **Golden/snapshot.** Generated code and JSONL/schema output are snapshot-tested
   (`.golden` files in VCS with a `--update` workflow) so drift is a diff, not a bug report.
8. **Conformance.** Every sink (built-in, integration, third-party) must pass the
   extended `LogSinkConformance` suite; adoption is enforced by a build task that
   fails if a `LogSink` subtype in a published module has no conformance subclass.
9. **Mutation gate.** PIT mutation score ≥ 80 % on `ilogger-core` engine files,
   ≥ 75 % per integration module, as a new phase gate. Coverage that cannot kill a
   mutant is not evidence.
10. **Determinism.** No test may sleep on wall time or depend on `Thread`s,
    default `Random`, ordering of `HashMap`s, host locale/TZ, or execution order
    (enforced with JUnit `FixMethodOrder`-free rerun and a repeat-twice task).
11. **Doc/claim verification.** New `:doc-checks` module: the compatibility matrix,
    constants table, annotation reference, diagnostics catalog, coverage floors,
    artifact list and claims in this file are generated from code/build inputs and
    compared to `docs/**` in CI. This is what lets D1-D15 exist and be closed.
    The same rule applies outward: every competitor-parity claim names the version
    it was checked against (§2 survey line), and every "ILogger lacks X" row must
    cite the code path that proves the absence — a gap claim with no evidence line
    is not allowed into the register.
12. **Perf non-regression.** Budgets (section 9) are enforced per subsystem, each
    scored by fastest-of-three runs with a noise policy already proven in
    `BudgetGateTest`; a budget without a benchmark is a phase blocker.

---

## 6. Phase 0 — Truth and hygiene (P0, 1 week)

*Closes the credibility defects so the rest of the plan is measured against facts.*

- **G-45 Claims audit & single source of truth.** Generate the support matrix
  (minSdk, JVM target, Kotlin/KSP/Room/Ktor/OTel pins, coverage floors, API dump
  list, artifact list) from the build; delete or correct every contradicting doc
  line (D1-D4, D9-D15, I2 numbers). Fix the redaction/limits order text, publish
  `MAX_STRUCTURE_DEPTH` and make it configurable and consistent with `EventLimits`.
  Read `reason` on `@LogSensitive`/`@LogExclude` and surface it in diagnostics, or
  remove the parameter. Scope the docs/16 "audit is never silently filtered" claim
  to *level* filtering and state the overflow reality next to it (D15) — until
  G-50 makes the claim unconditional.
- **G-46 CI completeness.** Named jobs for every published module (incl. remote,
  otel, slf4j, jul), the full OS matrix for them, a BOM-completeness check, a CLI
  distribution smoke run, `:app:assembleRelease` with R8 and an APK-size budget, and
  androidTest compilation *and execution* in the emulator job.
- **G-47 Doc-sync module.** `:doc-checks` verifies constants (docs/24), annotation
  parameters (docs/04), diagnostics catalog (docs/23), sinks table (docs/14) and
  README feature claims against code. Add the missing 9 negative fixtures and
  fixtures for `ilogger.strict`/`ilogger.enabled=false` (D12).

**Phase 0 test gate:** `:doc-checks` in CI (fail on any drift); matrix = 14
artifacts × {documented, actual} values for minSdk/JVM/Kotlin/Room/Ktor/OTel;
every roadmap `Status:` line in this file re-verified by a named test or an
`Evidence:` path (checked by a script that greps the cited symbols exist).

---

## 7. Phase 1 — Delivery truth and engine hardening (P0, 4-6 weeks)

*The audit trail must be provably complete, bounded, and fast under real load.*

- **G-48 Total loss accounting.** One `DropReason`-typed, counter-backed record for
  *every* loss path: dead-letter overflow, enricher/adapter/redactor failures,
  suppression, sampling, strict rejects (split `STRICT_REJECT` into `LIMITS`,
  `REDACTION`, `STRICT_VALUE`), closed, retry-exhausted, capacity. Add
  `DropReason.DEAD_LETTER_OVERFLOW` and count it. Never construct a `DroppedEvent`
  with `event = null` where a body exists.
- **G-49 Honest barriers.** `FlushReport`/`CloseReport` gain `sinkFailures`,
  `deadLetterPending`, `droppedSinceLastFlush`; `flushDistinctSinks` reports
  instead of swallowing; `ILogger` interface defaults that fake guarantees are
  either implemented or the API becomes `abstract`/sealed-in-practice (see G-64).
- **G-50 Byte-bounded memory + lock-free admission.** Per-binding byte budget
  (not just event count), aggregate budget across bindings, `limits`-independent
  hard ceiling; replace the logger-wide `admissionLock` with per-pipeline locks
  plus lock-free stats (LongAdder snapshots); move sampling decisions off the
  global monitor; make `isBacklogged`/`availableCapacity`/`stats` non-blocking
  reads of atomics.
  **Level-aware shedding (fixes E15, and makes D15's promise true):** eviction
  under `DROP_OLDEST`/`DROP_NEWEST`
  must never select an `AUDIT` (or otherwise configured-protected) event while a
  sheddable TRACE/DEBUG event is queued — a per-binding
  `discardingThreshold`-equivalent ("shed below this level first, then by age"),
  with shed volume still counted per `DropReason` (G-48); plus `neverBlock`
  (return immediately instead of parking when full) and a bounded per-binding
  flush wait, so a saturated queue cannot ANR a main thread. Precedent: Logback
  `AsyncAppender` `discardingThreshold`/`neverBlock`/`maxFlushTime`, NLog
  overflow actions.
- **G-51 Idempotent, per-event delivery.** Per-event acknowledgement in
  `writeBatch` (partial-failure retry sends only the un-acked subset), a
  documented delivery contract per sink (`at-most-once`/`at-least-once`/
  `exactly-once-if-idempotent`), and a `DeduplicatingSink` decorator keyed by
  event id with a bounded window.
- **G-52 Visibility, immutability and safe toggles.** `enabled`/config snapshot
  `@Volatile`-correct or atomic-referenced; post-build config becomes immutable
  (`ValueAdapterRegistry` frozen, `ExtractorRegistry` registration sealed by a
  lifecycle), with an explicit `reconfigure` path (G-57) instead of ambient mutation.
- **G-53 Durability & rotation contract.** Multi-process-safe file sink (OS file
  lock + per-process segment files + `.lock` metadata) **and an opt-in shared
  writer mode** — one owning writer process/agent that serializes append,
  rotation and chain state for N producers (tinylog ships this as a first-class
  `Writer` setting; E17); documented durability levels with tested power-loss
  semantics; a named **rolling policy set** rather than two knobs
  (`daily|monthly|size|startup|dynamic`-style, composable, plus cron-like
  schedules), name patterns (`yyyy-MM-dd`, index, compressed suffix),
  `maxTotalBytes` edge cases (cap < active file), gzip archive integrity, and a
  `HashChainSink` **segment** model (per-process segment id + persisted sequence
  + verifier that proves continuity rather than accepting intermixed chains).
- **G-54 Suppression, dedupe and log-once.** Prefix matching for
  `suppressSources` as documented; `logOnce(key, window)` / `DeduplicatingSink` /
  `RateLimitedSink` / `RetryingSink` / `FilteringSink` / `BufferingSink`
  decorators shipped in core; a `RingBufferSink` (bounded, inspectable) for
  in-app and CLI tail of a live process.
- **G-55 Allocation diet on the hot path.** Zero-allocation rejected path (level/
  filter/disabled checked before any boxing), reuse of batch buffers, avoid the
  double metadata/changes copy, cache `routesFor` per category, drop the
  per-admission `DroppedEvent` list allocation, add an allocation-budget JMH
  assertion (`gc.alloc.rate.norm`) rather than only ns/op.
  **Garbage-free diagnostics lane (fixes E16):** a separate, deliberately
  log4j2-shaped path for `trace…error` — reusable record builders, no
  `Pair[]`/`EnumMap`/list churn, an optional pre-allocated ring per binding —
  with a published bytes-per-event target (≤ 1 object per admitted event on the
  diagnostics path, 0 on the rejected path) and GC-garbage measured per second at
  a fixed event rate, not only ns/op. The comparison to beat is documented:
  normal vs garbage-free async in log4j2 is ≈ 809 MB/s → ≈ 1.6 MB/s of garbage,
  which is why incumbents hold ~800k+ msg/s on hardware that ILogger's current
  ~1 µs/op single-producer figure only approaches in the best case.

**Phase 1 test gate (blocking):**
- Loss matrix: 9 loss sites × 3 dispatch modes × 4 overflow policies ×
  3 error modes = **324 cases**, each asserting exactly one counter increment,
  one `onDrop`, and dead-letter presence/absence rules.
- Barrier matrix: {idle, mid-drain, full queue, slow sink, throwing sink flush,
  dead-letter backlog} × {flush(), flush(timeout), awaitIdle, close,
  closeAndReport} = **25** with virtual time and `sinkConcurrency ∈ {1,4}`.
- Memory: byte-budget × event-size sweep at boundaries {1 B, 1 KiB, default
  budget ±1, unbounded}; OOM-shaped generator test proving no allocation growth
  when disabled.
- Shedding: fill each pipeline to capacity with every permutation of
  {TRACE, DEBUG, INFO, WARN, ERROR, AUDIT} × {DROP_OLDEST, DROP_NEWEST, BLOCK,
  FAIL} × `discardingThreshold ∈ {0, 1, k, all}` and assert an AUDIT event is
  **never** the eviction victim while a sheddable event exists, that every shed
  increments exactly one counter, and that `neverBlock` returns without parking
  (measured, not asserted by comment).
- Garbage: JMH `gc.alloc.rate.norm` per admitted diagnostics event and bytes/s of
  GC garbage at 100 k events/s, gated as a regression budget alongside ns/op.
- Idempotency: partial-failure injection over batches of {1, 2, 7, 128} with
  10⁴ seeded failure patterns asserting zero duplicates for idempotent sinks and
  documented duplicates for non-idempotent ones.
- Rotation/durability: OS matrix (Linux/macOS/Windows) × `maxFiles ∈ {0,1,2,5}` ×
  size/time/cron triggers × gzip × cipher × concurrent writers × kill-9
  mid-write, asserting no gap, no `FileAlreadyExists`, archive readable.
- Concurrency: JCStress litmus set for `enabled`, watermarks, `stats`;
  30-minute soak with leak detection (heap delta < 1 % over steady state).
- Mutation ≥ 80 % on `DefaultILogger.kt`, `Delivery.kt`, `EventLimits.kt`;
  Kover core floor raised 78 → **90** line + **85** branch in this phase.

---

## 8. Phase 2 — Facade completeness (P1, 6-8 weeks)

*Everything a mature logging facade offers, on top of the audit model — this is
the single largest adoption gap.*

- **G-56 Named and child loggers.** `ILogger.getLogger(name)` with a tree
  (dot-separated hierarchy, nearest-configured-ancestor inheritance), `logger.child(
  category, level, attributes)` reusable handles, `withScope {}` lexically-scoped
  fields, and per-logger overrides of level/filters/bindings. Backward-compatible
  with today's single-instance `create{}`.
- **G-57 Runtime reconfiguration.** `logger.reload { … }` (atomic swap of a
  frozen config, drain-before-discard option, in-flight ordering guarantee),
  `setLevel(level, ttl)`, `addSink/removeSink`, `ConfigHandle` rollback, a
  config **source SPI** (file watch, HTTP, Consul/etcd, System properties) with
  signed-source verification, and every change itself audited (G-92).
  Ship the ergonomics people actually reach for, not just the primitives: a
  Timber-`plant`/`uproot`-shaped attach/detach API for the debug→release switch
  (install console+Logcat in debug, swap to Room+shipping in release, swap to
  fakes in tests) as one call with a proven in-flight drain, plus
  `logger.effectiveConfig()` returning the live bindings, levels, filters, stage
  order and budgets as data (closes A16) so an incident responder can read the
  truth instead of the source.
- **G-58 Diagnostics API completion.** `trace/debug/info/warn/error` overloads
  with lazy `() -> Any` message suppliers and parameterized `{}`/named templates,
  `(message, throwable, fields)` overloads, `notice`/`fatal`(+panic-like
  flush-then-rethrow) levels, level aliases, `isTraceEnabled(name, marker)` fast
  checks, and `check(level) { }`/`CheckedLog` so rejected paths never build fields.
  **Level model made extensible (closes A10):** user-defined levels with explicit
  ordinal/int values (so a bridged stack can define `VERBOSE`/`FATAL` without
  contorting into the built-in six), per-bridge **level offset/mapping tables**
  (log4j2 `LevelOffset`-style: JUL `FINEST`→TRACE, `SEVERE`→ERROR, and a
  third-party `NOTICE` that must not collapse into INFO), and level-range
  thresholds usable wherever a filter is accepted (`LevelRangeFilter` parity) —
  each level additionally carrying its OTel severity number 1-24 for Phase 5.
- **G-59 Markers, tags, caller data and audit spans.** `Marker`/`tags` on the
  event — named, **hierarchical** (a marker carries parent markers, so a filter on
  `AUDIT_PAYMENT` matches `AUDIT`-tagged events like Logback markers do) and
  serializable into every encoder's field map; a `TurboFilter`-style
  pre-construction veto SPI running before any allocation whose decision may
  DENY, ACCEPT, **or rewrite the level** (Logback's `TurboFilter` can neuter a
  chatty site to DEBUG or force an ERROR through — a shedding and incident
  triage tool, not just a mute button), with recursion immunity and per-site
  budget accounting; opt-in caller location implemented as **data from the call site**
  (KSP-generated `file/line/function`, zero stack walk) and a reflective-free
  `StackWalker` fallback for manual calls; `auditSpan("checkout") { … }` giving
  ENTER/EXIT with duration and inherited fields, with `with_span_events`-style
  optionality.
- **G-60 Ambient context to parity.** Merge `LogContext.attributes` into event
  metadata (opt-in, key-namespace rules, precedence explicit > child > ambient),
  typed attributes (`Any?`, not `String` only), NDC-equivalent push/pop stack,
  coroutine-native propagation via a `CoroutineContext` element + a kotlinx
  `asMDCContext()` interop bridge, and thread-pool inheritance for
  non-suspend bridges.
  **Decide the semantics SLF4J leaves open (closes A5):** publish the
  copy-on-enter / clear-on-exit rules explicitly (the
  `MDCContext(copyOnEnter=…, clearOnExit=…)` matrix — kotlinx.coroutines #4187 is
  still unresolved, so ILogger must state and test its choice rather than inherit
  an accident); register a Micrometer `context-propagation` `ThreadLocalAccessor`
  so executor service wrappers (`ContextSnapshot`/`captureAll`) carry ILogger
  context through *foreign* thread pools; keep context out of the event unless
  opted in, but make the merge order and key-collision behavior a documented,
  tested table (every transit point × every operator in `{launch, async,
  withContext, Flow.collect, flowOn, channel, SharedFlow, runBlocking,
  ExecutorService.submit, WorkManager worker}` — both directions).
- **G-61 Filtering, routing and the pipeline.** Replace category-equality routing
  with a `SinkBinding` that carries level range, predicates (`when { }`),
  categories, marker sets, sampling overrides and *its own* dispatch/batch/retry
  profile; a `LogEventProcessor` pipeline where the consumer declares the order
  of enrich/adapter/redact/limits/sample/route/render (structlog-style,
  with `foreignPreChain` for bridged events); `SiftingSink` for dynamic child
  sinks keyed by a value (tenant, user, file).
  **Filters as declarative data, not only lambdas (closes A4/A7):** composable
  matcher objects — `LevelRangeFilter`, `MarkerFilter`, `CategoryFilter`,
  `ActionFilter`, `OnMatch THEN/NEXT` semantics, and a regex/JSON-path
  `ExpressionFilter` — so a binding's routing can be *written in config, compared
  and diffed* (Logback `EvaluatorFilter`/`ch.qos.logback.classic.filter.*`,
  NLog `when`), with deny-wins ordering proven. **Sifting knobs must be bounded
  like Logback's:** discriminator key + default value, idle `timeout` eviction,
  `maxSinks` ceiling with a counted overflow drop reason (an unbounded
  per-tenant/per-user sink map is a memory leak and a cardinality attack), and
  deterministic close/drain of sifted children.
- **G-62 Envelope fields.** Add `sequence`, `observedAt`, `durationNanos`,
  `threadName`, `coroutineName`, `appName/appVersion/buildType`, `processId`,
  `hostName`, `sdk` + `schemaId`, with `ilogger.*` reserved-key governance,
  and OTLP/ECS/Loki mappings for each (Phase 5).
- **G-63 ID and sequence strategies.** `EventIdGenerator` SPI with UUIDv4 (today),
  ULID/KSUID time-ordered, snowflake-style, and a persisted monotonic `Sequence`
  per stream with restart continuity (feeds G-90); `isLoggable` must become
  side-effect-free (no permit consumption) or explicitly documented as a probe.
- **G-64 Contract-honest `ILogger`.** Turn the accidental defaults into either
  working base implementations or `abstract` requirements; publish an
  `AbstractILogger`/decorator base so third-party wrappers inherit barrier/
  backpressure semantics correctly; reduce the parameter surface by deprecating
  the 14-arg overloads in favor of drafts (A15) with a documented v3 removal.
- **G-65 Raw ingestion and replay.** `ingest(LogEvent)` (id/timestamp preserved,
  bypasses generators), `ReplaySink`, `DeduplicatingSink` interplay, and offline
  import from JSONL/Room into another store (already CLI-side; make it API).

**Phase 2 test gate:** facade matrix = {named/child/root} × {level, range,
predicate, marker, category} filters × {eager, lazy, parameterized, throwable,
checked} call shapes × {suspend, blocking, thread-local, coroutine-switched}
context = **480** enumerated cases; context matrix proving attributes reach the
event across `withContext`, `launch`, `Flow` collection, `Dispatchers.IO` hop,
and a `runBlocking` boundary (**all 6** transit points, both directions, plus
NDC depth 5); pipeline-order tests asserting the consumer's declared stage order
(permuted with a covering array of 6!→t-wise 3, ≥ 120 cases); reconfiguration
matrix {reload, setLevel+TTL, add/remove sink} × {idle, mid-batch, full queue,
failing sink} × {concurrent readers} with no loss/dup beyond documented
contracts; markers/turbofilter veto tested at the allocation level (JMH
`gc.alloc.rate.norm == 0` on rejected paths) **and for all three decisions**
(DENY, ACCEPT, level-rewrite) including marker-hierarchy matching; matcher-filter
matrix = every filter type × {on-match accept, on-match deny, on-mismatch next,
chained 2-deep with conflicting verdicts} × {event above/in/below range}, with a
deny-wins assertion per chain; sifting matrix = {discriminator present, absent →
default, unbounded-key attack} × `maxSinks ∈ {1, k, exceeded}` × idle eviction ×
close/drain proving no sink leaks and overflow is counted; attach/detach matrix
(Timber-shaped) over {debug→release, release→test, double-attach, detach mid-batch};
mutation ≥ 80 %, new-code line coverage 100 % for every public symbol added in
this phase.

---

## 9. Phase 3 — Codegen reach and build DX (P1, 6-8 weeks)

*Instrument everything that can be annotated, and make incremental builds provably correct.*

- **G-66 Shape completion.** Recognize `StateFlow`/`SharedFlow`/`Channel`/
  `Sequence`/`ReceiveChannel` and Rx `Publisher`/`Single` with documented
  semantics; support `object`/companion/enum/sealed members with container-aware
  diagnostics (not the generic ILG001); reject `private`/`local`/`inline`/
  `expect`/`actual`/`operator` with specific codes instead of emitting
  non-compiling code; stop silently skipping Java/cross-module sites (diagnostic
  or support).
- **G-67 Usability of generated wrappers.** Match wrapper visibility to the
  declaration (`internal`→`internal`, public→public), generate `logged()` for
  class wrappers, allow explicit-API-module consumers, keep `@NoLog` wins
  discoverable (info diagnostic), and add per-module opt-in mass adoption
  (`ilogger.on-by-default` with `@NoLog` exclusions) so adopting teams can flip
  instrumentation on without annotating every method.
- **G-68 Compose & Android UI instrumentation.** `@Log` on `@Composable`
  (composition/visibility/interaction events, preview-safe no-op), a
  `@LogLifecycle` for Fragment/Activity/ViewModel lifecycle + `NavBackStack`
  events, `@Preview`-safe generation, and generated code that survives R8
  minification with shipped, tested keep rules.
  **Minification must become a supported configuration, not an assumption
  (closes C15):** real `consumer-rules.pro` content (the current file is a single
  `-dontwarn`), documented and tested `-keepnames` for generated adapters,
  `@LogActions` constants and `@LogConfig` targets, and a decision for the
  failure-audit defect that today only debug builds survive — `LogFailure`
  records `error::class.java.name`/`rootCauseType`, which R8 renames, so either
  ship the keep rule that preserves exception names in the audited set or
  generate a stable failure-name identifier at compile time. Gate it with a CI
  `assembleRelease` + `minifyReleaseWithR8` run that executes the audit flow on a
  device/emulator and asserts readable exception names (today CI only builds
  `assembleDebug`, so nothing proves any of this).
- **G-69 Correctness of existing rules.** Fix the Flow×INLINE /
  Flow×onCancellation false rejection (D11); reconcile docs/24; add cross-rule
  fixtures (every option × every function-shape combination ≥ covering array).
- **G-70 Incremental correctness proven.** Complete the dependency graph (entity
  file, `@LogConfig` file, extractor/adapter files, option changes), bound the
  deferred-symbol rounds with a diagnostic when exceeded, and land a real
  compile-testing harness (kotlin-compile-testing / KSP2 test runner) asserting
  UP-TO-DATE, ab-fresh, and "change one file → regenerate only its wrappers".
- **G-71 Schema aggregation.** Cross-module manifest merge (build cache +
  `ilogger-bom` coordinates), a registry check task (`iloggerSchemaCheck`) that
  fails on collisions across modules, JSON Schema/OpenAPI-style export per
  action, and diffing between releases in CI.
- **G-72 IDE experience.** IntelliJ plugin (or K2 extensions): inspection for
  every diagnostic, quick-fix that applies the `Fix:` text, gutter annotation of
  the action, `.logged()` generation intention, and an inlay showing the
  inferred entity/id. Ship static-analysis artifacts too, the way Timber ships a
  lint module: an Android **Lint** check and a **detekt** rule that flag
  unstructured escapes inside an audited module (`Log.d`, `println`, `e.printStackTrace()`,
  raw `Timber.tag(..)` where an `@Log` action is expected) as build-time findings
  with the ILogger replacement in the message — enforcement belongs next to the
  code, not in a review comment.
- **G-73 Runtime-visible contracts (opt-in).** `@Retention(RUNTIME)` variant or
  a `META-INF/ilogger/actions.bin` compiled export so tooling/registries can read
  a jar (C8), with a size budget and an opt-out flag.
- **G-74 Gradle plugin hardening.** KMP target support, fail loudly (not silently)
  when no Kotlin plugin is applied or when the plugin version mismatches core,
  `ilogger { }` DSL for decorators/filters config-as-code, a `iloggerCheck`
  aggregate task, configuration-cache and isolated-projects compatibility,
  TestKit tests that assert dependencies and real variant disabling, and plugin
  coverage/API checks inside the root quality gate.
- **G-75 Compile-time budget.** Measure and gate KSP overhead (added seconds per
  module, generated LOC, `.class` size) with a benchmark module that compiles a
  realistic 500-site codebase; publish the numbers.

**Phase 3 test gate:** shape matrix = {interface, open class, abstract, top-level,
extension, object, companion, enum, sealed, inner, private, local, inline,
expect/actual, Java} × {suspend, non-suspend, Flow, StateFlow, Channel, Sequence,
Result, generic, vararg, overload, Composable} = **full Cartesian where legal
(≈ 275)**, each with a positive fixture *or* an exact-message negative fixture;
100 % of the 36 diagnostic codes with at least one verbatim negative fixture and
one positive "stays silent" fixture; incremental matrix {no-op, single file,
annotation-only edit, entity rename, extractor edit, option flip} × {UP-TO-DATE,
rerun-only-what-changed} proven in a real build; Compose matrix (composition,
recomposition skip, navigation, process death) on emulator; R8 matrix
{debug, release minified, release shrunk} asserting events still emitted and
names stable; plugin matrix {jvm, android app, android lib, kmp-jvm, kmp-native,
none} with dependency assertions; compile-time budget within ±10 % of baseline;
lint/detekt rules tested as expect-error/expect-clean fixture pairs (including the
"no raw `Log.d` in an audited module" finding and its `@NoLog` escape); the R8
matrix runs the audit flow on a minified release build and asserts
un-obfuscated failure type names inside the recorded events.

---

## 10. Phase 4 — Platform reach (P1 for Kotlin-first teams, 8-12 weeks)

- **G-76 KMP core.** Split `ilogger-core`, `-annotations`, `-serialization`,
  `-testing` into common + platform source sets with `expect/actual` for clock,
  ids, hashing, atomics, thread/coroutine context bridge and dispatchers; publish
  JVM, Android, iOS (arm64/x64/simulator), macOS, JS, WasmJS and Linux/Native;
  no JVM types in the common API (Kotlin-native `Instant`/`Uuid` value types);
  Kermit-style minimal `PlatformWriter` per target while keeping the pipeline in
  common code (swift-log lesson: source location is data, not a stack walk).
  Adopt Kermit's writer *model*, not just its target list: a composable ordered
  writer set with **per-writer minimum severity** and per-writer formatter, a
  message splitter parameterized by each platform's real line limit (Logcat's
  3 800-char chunking exists; its 23-char **tag** limit pre-API 26 is not handled
  and silently breaks tag-based filtering — clamp or hash-and-suffix the tag),
  `os_log`/`OSLog`-shaped iOS output with subsystem+category, JS `console` with
  object grouping, plus the native equivalents of `debugLog`/`releaseLog`
  selection so a shared module does the debug-vs-release swap by itself.
- **G-77 Room KMP + query performance.** Adopt Room 2.8 KMP (`iosMain` etc.),
  ship v2 of `RoomLogSchema` with a real migration path + schema-drift test,
  add indices for `actorId`/`entityId`/`correlationId`/`category`/`source`,
  switch public paging and export to keyset, filter by `source`/`category`, add a
  `PagingSource`, FTS option for payload search and documented WAL/
  busy-limits/multi-process policy.
- **G-78 Android production completeness.** Reconcile minSdk truth; make
  `ILoggerAndroid.install` the app's actual path (dogfood) and cover it with
  instrumented tests; crash-report directory bounds + gzip; spool size caps;
  transport instance injection (no process-wide `@Volatile`); `ApplicationExitInfo`
  (API 30) and `onTrimMemory` ingestion; ANR-safe guarantees with a strict-mode
  test proving zero main-thread IO beyond `recordBlocking`; user-consent gate and
  roaming/idle/charging WorkManager constraints; a library-owned debug log viewer
  (closes I14)
  (Compose, `RingBufferSink` + `observe`) shipped as a separate debug-only
  artifact — the field is open (Meta retired Flipper; Lens, Chucker and
  DbInspector prove teams want **zero-ADB** on-device inspection of what the app
  really recorded), so make it read the actual Room store with
  filter-by-action/actor/entity, chain-verification status, spool depth and an
  export/share path instead of mirroring `Log.d`; release-build APK/dex size
  budget with R8 in CI.
- **G-79 KMP for integrations.** `ilogger-remote` + `ilogger-serialization` +
  `ilogger-ktor` client targets for non-JVM; native-safe TLS/config injection;
  coroutine-context-safe OTel propagation on every platform (ties into G-81).
  **Crash reporting as an adapter SPI, not one Android class (closes I13):**
  Kermit ships per-service crash adapters (Crashlytics, Sentry, Bugsnag) and the
  audit library should match that surface with a `CrashAdapter` SPI plus
  first-party implementations — each recording the crash **as an audit event**
  (actor, correlation, last-known sequence, spool state) before handing off, with
  a bounded, rotated report directory and a documented "the next launch reports
  it" contract. Add iOS `os_log`/`NSException`, JS `window.onerror` and a JVM
  `UncaughtExceptionHandler` variant so the pattern is not Android-only.

**Phase 4 test gate:** target matrix = 8 targets × the engine's Phase-1 loss &
barrier matrices (reduced t-wise 3, ≥ 240 cases each) run in `commonTest`;
context matrix per platform (incl. main-thread, background queue, `withContext`);
Room matrix {v1→v2 migration, indices used (query-plan assertion), keyset paging
at 10⁵ rows, FTS, WAL, multi-process} per supported driver; instrumented suite
expanded from 4 → ≥ 40 cases including crash handoff, WorkManager upload with
constraints and consent, and StrictMode violations failing the build; size gate
APK delta ≤ documented budget on a reference app; writer matrix = every platform
writer × {per-writer minSeverity boundary levels, message at {limit-1, limit,
limit+1, 10× limit}, tag at {1, 22, 23, 24, 200} chars, multiline, unicode,
empty} asserting no silent truncation anywhere; crash-adapter matrix = 4 adapters
× {OOM, native crash, ANR, uncaught in coroutine, kill -9, next-launch replay}
proving the audit record exists before handoff and the report directory stays
bounded.

---

## 11. Phase 5 — Wire formats, encoders, backends and read-models (P1, 7-10 weeks)

- **G-80 Encoder SPI + standard formats.** Separate **layout** (human text with
  patterns/alignment), **structured encoder** and **wire encoder** (bytes) from
  sinks; extend `EventCodec` to a byte-oriented SPI with streaming; ship
  `EcsEventCodec`, `ClefEventCodec`, `GelfEventCodec`, `SyslogRfc5424Codec`,
  `LogfmtCodec`, and a `PatternLayout`-equivalent text renderer with pluggable
  conversion rules (`%logger`, `%marker`, `%mdc{k}`, `%caller`, `%change{k→v}`,
  colorized ANSI profile); mapping tables published per format, in `docs/`, and
  machine-checked by the golden-file suite.
  **Encoders must be configurable as data, not only as code (closes O1):** a
  shared field-mapping spec — include/exclude/rename/add per field, with dotted
  paths and conditional adds — so `ilogger { }`/`ilogger.yaml` can express
  "emit ECS but rename `actor.id` → `user.id`, drop `metadata.internal.*`" the way
  `JsonTemplateLayout` and Spring Boot's `logging.structured.json.*` do, and the
  console encoder is selectable per format (`ecs|gelf|logstash|clef|logfmt`)
  because that is now the *default expectation* for JVM server apps, not an
  add-on.
- **G-81 OTLP logs exporter.** `OtlpLogSink` (HTTP+protobuf and HTTP+JSON) with
  severity-number mapping (1-24), `time`/`observed_time`, `trace_id`/`span_id`/
  `trace_flags` from G-59/OTel, `event.name` = action, resource/scope attributes,
  partial-success/retry semantics, compression, and env-var SDK-style config.
- **G-82 Loki and Elasticsearch/Legacy backends.** Loki push sink with an
  explicit **label allow-list** (low cardinality) and structured-metadata split,
  tenant header, and backoff on `429 X-Rate-Limit`; Elasticsearch bulk sink
  (ECS data-stream `logs-*-*`, `_id` = event id for idempotency);
  CloudWatch EMF and GCP payload shapes as encoders.
  **Ship destinations as transport × encoder *pairs* (closes O15), not new
  sinks:** the reusable matrix is `{HTTP, Kafka, AMQP/JMS, JDBC batch, SMTP,
  syslog UDP/TCP/TLS, file/socket}` × `{bespoke JSONL, ECS, CLEF, GELF, RFC 5424,
  logfmt, OTLP, EMF}`, and each backend ticket is "add the missing transport" or
  "add the missing encoder" plus its pairing test — which is why the incumbents'
  20-40 appenders collapse into ~7 transports and ~8 encoders here instead of 40
  sinks. Batch-write semantics matter for audit: the JDBC transport must use a
  real batch with per-row failure reporting (G-51), Kafka/AMQP must key by
  correlation id and prove ordering-or-not per partition, and every pair inherits
  `LogSinkConformance` + the Phase-5 transport failure matrix.
- **G-83 Remote engine correctness.** Ship a real OkHttp (or Ktor) transport for
  JVM+Android, replace the single-mutex drain model with per-connection workers,
  O(1) spool ack (segmented spool + index), adaptive batch sizing from observed
  RTT and server `Retry-After`/429 hints, half-open circuit probes,
  client-credentials auth with 401 refresh-and-replay, mTLS/proxy/HTTP-2
  configuration, and per-sink `FlushReport` truth.
- **G-84 Bridge completeness.** Declarative configuration for the Logback appender
  and JUL handler (no-arg constructor + `ILogger` lookup strategy), Log4j2
  appender, MDC writing on the outbound path, Marker/`ThreadContext` forwarding,
  `TurboFilter` interop, and a kotlin-logging/SLF4J-fluent-compatible facade so
  existing code can compile unchanged.
- **G-85 Out-of-process transport (optional).** A sidecar/worker transport (pipe
  or local socket) so serialization and network IO live outside the app's failure
  domain (pino/loguru lesson), with crash detection and restart semantics.
- **G-111 Entity revision history (read-model, closes I12).** Audit *writes* already
  exist; the *read* that examiners and app screens ask for does not. Build the
  Envers/Spring-Data-auditing/Javers-equivalent on top of the Room store:
  `revisionsOf(entity, entityId)` (ordered revision list with actor, outcome and
  field diffs), `stateAsOf(entity, entityId, timestamp)` (reconstructed field map
  at a point in time, including "did not exist yet"), `diff(entity, id, from, to)`
  and `changedBy(actor, range)`, backed by a materialized revision index with a
  documented compaction rule (snapshots + deltas, so reconstruction stays O(log n)
  instead of replaying every event), legal-hold-aware and erasure-aware (a
  redacted revision is visible as redacted, never as absent), plus the same API
  shape over JSONL files so the CLI and the app agree on the answer.
- **G-112 HTTP audit contract, Logbook parity (closes I7).** Raise `ilogger-ktor`
  from "method/path/status metadata" to a request/response audit policy engine:
  opt-in **body capture with a safety contract** (`bodyOnlyIfStatusAtLeast`,
  max-bytes preview, binary/streaming refusal, never capture by default),
  declarative header/query/path/body **filters with obfuscation** (an
  allow-list-first default, so `Authorization`/`Cookie`/tokens cannot leak by
  omission), a **correlation strategy** SPI (header → generated id → downstream
  propagation, with `Correlation` id surfaced as a first-class event field rather
  than metadata), a **Compliance** mode that structurally forbids risky fields,
  route-template dimensions instead of raw paths (fixes the cardinality hole in
  I7), trusted actor resolution instead of a bare `X-User-Id` default (feeds
  G-98), WebSocket/SSE lifecycle events, and a client-side mirror of every policy
  so outbound calls are audited with the same rules — documented as one contract
  table shared by server and client.

**Phase 5 test gate:** encoder matrix = 6 encoders × {every field, empty, unicode,
nested, cycles, truncated, encrypted payload, unknown enum} × {round-trip where
decodable} = **≥ 500 cases** with golden files; OTLP conformance against the
official data model (InMemory exporter + `opentelemetry-sdk-testing` assertions
for severity mapping, attribute typing, resource correlation, partial success on
`4xx/5xx`/rejected-log-records); Loki cardinality test asserting label set is
bounded by the allow-list and rejecting a config that would exceed a label
budget; ES bulk idempotency (`_id` collisions, retry storms); transport matrix
{success, 408, 429+Retry-After, 500, timeout, connection reset, TLS failure,
auth expiry} × {spool on/off} × {circuit states} × {1..64 MiB backlog} proving
monotonic drain, zero O(n²) writes (instrumented assertion), and no duplicates
for idempotent collectors; bridge matrix = all 6 levels × MDC/kv/throwable ×
{recursion, config-file loading, both bridges installed}; contract tests for
`WireCodec` + `Transport` SPI published for third parties; destination-pair matrix
= 7 transports × 8 encoders (cells with no supported pair fail the build with an
explicit "not shipped" table entry, so the claim surface is exact); revision-history
matrix = {as-of before creation, at first write, mid-series, after erasure, under
legal hold, after compaction} × {revision list, reconstruction, diff, changedBy} ×
{Room store, JSONL store} asserting both stores return byte-identical answers and
that reconstruction cost stays within the documented bound at 10⁵ revisions;
HTTP-audit matrix = {GET/POST/multipart/SSE/WebSocket, 2xx/4xx/5xx, oversized
body, binary body, streaming body, missing/duplicate correlation header,
authenticated vs anonymous} × {allow-list, deny-list, obfuscation rule, compliance
mode, body threshold} with a **leak scanner** that fails if any
`Authorization`/`Cookie`/`Set-Cookie`/known-secret-shaped value appears in any
captured field, and a cardinality assertion that the route template — not the raw
path — is what reaches metrics.

---

## 12. Phase 6 — Operations and dynamic control (P1, 5-7 weeks)

- **G-86 Admin surface.** `ILoggerAdmin` API (levels, per-logger overrides with
  TTL, sink list/state, drain, config rollback, **effective-config as data** per
  G-57/A16) exposed primarily as an **in-process, authenticated API** with a Ktor
  management endpoint and a Gradle/CLI client (`ilogger admin set -l payments
  -level INFO`) — every action audited (G-92), every grant TTL-bounded, with
  read-only mode and an explicit break-glass flow.
  **JMX is deliberately not the primary path (closes O2 safely):** Logback
  *removed* its JMX configurator in 1.3.0 because an unauthenticated MBean server
  is a remote-code-execution and log-flooding surface, and an audit library that
  can be reconfigured by any local process can be silenced by one too. Offer JMX
  only as an opt-in, hardened adapter — attach-only with a documented
  `com.sun.management.jmxremote.access` requirement, read-mostly defaults, no
  object-deserialization payloads, all writes gated by the same auth as HTTP, and
  a build/CI test that asserts a default configuration exposes **no** mutable
  MBean.
- **G-87 Self-diagnostics.** An internal status channel (ERROR/WARN/INFO/DEBUG of
  the library itself) with a hard recursion guard, one-shot-per-site dedupe,
  written to a bounded separate file/stderr/Logcat sink; failures *of* the
  dead-letter path and of redaction/limits/enrichers surface here, not only as
  counters (closes A12, Serilog `SelfLog`/log4j2 `StatusLogger` parity).
- **G-88 Health & SLIs.** `LoggingHealth` snapshot (per-binding state, queue age,
  drop rate, sink last-success, chain verification status, spool depth) exported
  as OTel metrics, Micrometer meters, a `/health`-shaped JSON, and an Android
  `HealthSheet`; alert rules published (`dropped_total > 0` for AUDIT = paging).
- **G-89 Shutdown contract.** Two-phase drain (stop accepting → drain → force),
  JVM shutdown hook registration (opt-out), Kubernetes `preStop` recipe with
  `terminationGracePeriodSeconds` math, Android-safe flush (no main thread),
  deterministic `installExitHook()`, and a documented "events after close" policy
  (drop-and-count, never throw).
- **G-90 Gap-free evidence.** Persisted per-stream monotonic sequence (G-63),
  continuity proof (`seq` holes ⇒ synthetic `audit.gap` record inside the chain),
  clock provenance record (source, max observed offset, backward-jump event),
  and a `verify` mode that reports *sequence* integrity as well as hash integrity.
- **G-91 Anchoring and custody.** Periodic Merkle root over N records/segments,
  RFC 3161 TSA request+token storage (with an offline-verifiable fallback),
  key-custody SPI (HSM/KMS/Android KeyStore, non-extractable write key),
  per-generation `key_id` in every record, separation-of-duties model (writer key
  ≠ reader key ≠ rotator), and a signed **evidence bundle** (records + manifest +
  public keys + chain proof) that a third party can verify with the CLI alone.
- **G-92 Audit the audit.** Auto-emit `log.read`, `log.export`, `log.query`,
  `log.level.changed`, `log.config.reloaded`, `log.purge`, `log.hold.set/released`
  as first-class audit events (into the same protected store), with truncation/
  redaction disclosures recorded in exports.
- **G-93 Persistent compliance state.** Legal holds persisted through a store SPI
  (Room/file/remote) surviving rollback; erasure emits a signed **deletion
  certificate** (scope, hash, counts, key generation, policy, timestamp, TSA
  stamp); retention schedules expressible as data (not code) and validated.

**Phase 6 test gate:** admin matrix = {get/set level, TTL expiry, override
precedence, add/remove sink, drain, rollback, effective-config read} × {idle,
load, failing sink} × {HTTP, CLI, opt-in JMX} = **≥ 210** with
authenticated/unauthenticated and audit-of-the-change assertions, **plus a
red-team set**: default config exposes no mutable MBean, unauthenticated writes
are rejected and *audited as rejected*, a granted TTL always self-expires under
virtual time, and an admin-fluent payload cannot inject a format string or
path into a sink. Self-log matrix: 8 injected internal failure classes ×
{recursion guard, dedupe, bounded file, rotation} proving no logging loop can
cascade and every failure appears at least once; sequence/gap matrix: kill -9 ×
{buffered, async, inline} × {1, 4096, overflow} events with restart continuity
and hole detection asserted byte-for-byte in the chain; anchoring: TSA
happy/expired/unreachable/mocked-CA paths, Merkle root mutation (modify any
leaf → root mismatch), bundle verification on a clean machine with only the CLI +
public key; custody matrix {software, HSM-mock, Android KeyStore-mock} ×
{rotation mid-write, key loss, dual reader/writer}; compliance-state matrix
{hold set/persist/release, rollback, erasure with hold, certificate tamper}.

---

## 13. Phase 7 — Security, privacy and audit depth (P1, 4-6 weeks)

- **G-94 Redaction everywhere.** Apply redaction/DLP to **all** event surfaces:
  non-String values, `LogFailure.message` and frames, `action`, `entity`,
  `source`, `category`, `actor.id`/attributes, bridged foreign events (with a
  `foreignPreChain`), and rendered text (last-mile scrub for pattern layouts);
  ship gitleaks/truffleHog-compatible rule packs, format-preserving masking
  (card last-4, email local-name hashing) and a `Masker` SPI so rules are
  data, not code.
- **G-95 Data classification and minimization.** Field-level classification
  (`pii`, `phi`, `secret`, `financial`, tenant tags) carried from annotation to
  wire metadata, per-sink refusal policies (`pii>2` cannot leave the region /
  cannot be exported unencrypted), sampling-vs-audit policy guard (audit can
  never be sampled unless explicitly enabled — currently true, must stay), and
  a `minimize` stage that drops declared-unneeded fields.
- **G-96 Audit-commit binding.** `auditSink(sink)` = intent-explicit, inline,
  propagate-on-failure, never-duplicate binding that throws a typed
  `AuditCommitException` to the caller (Serilog `AuditTo` parity) with a
  documented "commit or roll back" contract and a tested interaction with
  per-sink timeouts.
- **G-97 Crypto breadth and hygiene.** Byte-oriented encryption for binary
  codecs, key-provider contract (rotation, revocation, `keysForReading`
  semantics, graceful unknown-key handling), no shared `SecureRandom` bottleneck
  per cipher call, Ed25519 signing shipped as a first-class implementation
  (not a host wrap), and security documentation of the threat model per feature.
- **G-98 Multi-tenancy and residency.** Tenant resolved from trusted context
  only (never a request header default — fixes I7), binding→region routing map,
  **fail-closed** when no compliant binding exists, per-tenant keys/retention/
  quotas, and a residency contract test suite.

**Phase 7 test gate:** leak hunt = every field position (metadata keys/values,
nested map/list at depth 1-4, changes, failure message/frames/cause chain,
actor id/type/attributes, action/entity/source/category, payload, bridged MDC,
pattern-rendered line, exception in dead letter, export file, remote body) ×
{DENY, MASK, HASH, OMIT, redaction rule, DLP pack, LogSafeValue escape} =
**≥ 700** assertions with a scanner that fails if any raw secret token appears
in *any* sink output; commit-binding matrix {sink throws, times out, closes,
full, disabled} × {caller commits, retries, aborts}; crypto matrix {key sizes,
IV reuse stress (10⁶, all unique), tag tamper bit flips, unknown/revoked key,
rotation mid-stream, cipher absence}; residency matrix {tenant → region, absent,
mismatch, policy flip} proving fail-closed with counted drops, never silent
cross-region writes.

---

## 14. Phase 8 — Test ecosystem and quality gates (P0/P1, 4-6 weeks, partly in parallel)

- **G-99 Test-framework parity.** Re-express `LogSinkConformance` as a JUnit 5
  `@TestFactory`/`@RegisterExtension` (JUnit 4 variant kept), publish Kotest
  matchers (`event should haveAction("task.created")`), Truth extensions, Turbine
  `Flow` matchers for Room `observe`, and a MockK/integration-friendly `ILogger`
  fake; golden snapshot tooling with `--update` and CI diff artifacts.
- **G-100 Fakes for everything that talks out.** Shipped `FakeRemoteTransport`,
  `FakeShippingTransport`, `FakeRoomLogDao`, `FakeClock`, `FakeKeyProvider`,
  `FakeTrailSigner`, `FakeTsa`, `FakeLogcat`; delete the per-module hand-rolled
  versions.
- **G-101 Conformance everywhere.** Every `LogSink` in a published module
  (console, composite, trigger, hash-chain, logcat, android file, remote, bridge,
  span, OTLP, Loki, ES) must have a conformance subclass; a build task fails when
  one is missing; publish an external "sink certification kit".
- **G-102 Coverage, mutation and branch gates.** Floors raised: core 90 line /
  85 branch, KSP 85 (via fixtures + compile-testing), integrations 85, testing
  90, serialization 95; PIT mutation ≥ 80 % core / ≥ 75 % per module; new code in
  a PR requires 100 % branch coverage of its own matrix cells (diff-cover).
- **G-103 Matrix runner + soak.** A `MatrixTest` DSL that generates the declared
  case matrices from section 5 into parameterized tests (so the matrix *is* the
  spec and the count is asserted), plus nightly soak (2 h), flake detection
  (repeat 20×, any non-determinism fails), and OS/arch/Native matrix schedules.
- **G-104 Perf gates per subsystem.** Budgets and CI gates for codec encode/decode
  throughput, Room query/export/observe, remote drain, hash chain, redaction,
  KSP compile time, Android cold-start install and per-event allocation
  (`gc.alloc.rate.norm`) — with the fastest-of-three noise policy proven in
  `BudgetGateTest`.
- **G-105 Property/fuzz expansion.** Generators for the value grammar, redaction
  rules, `EventLimits`, KSP expression parser, Room query builder, JSONL round
  trips and integrity canonical forms; a JVM fuzzing engine (Jazzer) job and a
  ClusterFuzzLite-style nightly.

**Phase 8 test gate:** self-referential — the gate validates the gates: matrix DSL
must report per-subsystem case counts and fail when a declared cell is missing
(the count is asserted in the test name), mutation score and coverage floors wired
into `check`, conformance-adoption task, flake job, and a release-blocking
`QualityGate` aggregate that prints a human table of every subsystem × matrix ×
result.

---

## 15. Phase 9 — Tooling, distribution and supply chain (P2, 4-6 weeks)

- **G-106 CLI distribution.** Fat jar (shadow) + GraalVM native image + Windows
  `.exe` + Homebrew/Scoop packages, a config file (`ilogger.yaml`) + env-var
  fallbacks, `--verbose`, shell completion, and CI smoke runs of the packaged
  binaries on three OSes.
- **G-107 CLI capability.** `redact`, `hold add/list/release`, `purge` (dry-run +
  policy), `query --sql` (read-only guard), `diff` (two files/runs/schema),
  `schema --json-schema`, `verify --bundle`, `admin` client (G-86), `pull` from
  HTTP/Loki/ES read APIs, `analyze` (cardinality/outcome dashboards), SQLCipher
  support, and JSON/CSV/Parquet export.
- **G-108 Developer viewers.** TUI tail (lnav-like filters), a Compose/`RingBuffer`
  on-device viewer, IntelliJ console filter + structured-log explorer plugin,
  web viewer for JSONL with verification, and Grafana/Loki dashboards + LogQL and
  Lucene query templates shipped in `docs/`.
- **G-109 Supply chain.** CycloneDX SBOM per release, SLSA L3 provenance
  attestations, Sigstore/cosign bundle signing, OpenSSF Scorecard runs with
  findings tracked, dependency CVE + license gates, and a documented security
  policy/CVD process + per-module threat model.
- **G-110 Toolchain currency.** KSP2 compatibility, Kotlin 2.4+/Wasm targets,
  Gradle configuration-cache/isolated-projects cleanliness, BCV/klib validation
  for multiplatform artifacts, and a documented compatibility SLA per toolchain
  (verified by the `:doc-checks` matrix).

**Phase 9 test gate:** packaging matrix = 4 distributions × 3 OSes × {help, tail,
verify, export, diff, admin, exit codes 0/1/2} on a real binary; CLI behavioral
matrix on fixtures (tampered/gap/encrypted/compressed/multi-segment/SQLCipher)
with golden outputs; SBOM/provenance verification job (`cosign verify-blob`,
`syft` diff against the published module list); scorecard threshold gate;
toolchain matrix CI (Kotlin 2.2/2.4 × KSP1/KSP2 × Gradle 8.x/9.x) — failures are
blocking for the "supported" claim in docs/38.

---

## 16. Sequencing and dependencies

| Release | Theme | Phases / goals | Depends on |
|---|---|---|---|
| **2.1** | Truth + delivery hardening | 0 (G-45…47), 1 (G-48…55) | — |
| **2.2** | Facade completeness | 2 (G-56…65) | G-48/G-49/G-52 (barrier + config immutability) |
| **2.3** | Codegen reach | 3 (G-66…75) | G-59 (caller data), G-62 (envelope) |
| **2.4** | Formats, backends & read-models | 5 (G-80…85, G-111, G-112) | G-62, G-63, G-83 needs Phase-1 idempotency; G-111 needs G-77 (Room indices/keyset paging) |
| **2.5** | Operations | 6 (G-86…93) | G-57 (reload), G-90 needs G-63 (sequence) |
| **2.6** | Security depth | 7 (G-94…98) | G-61 (pipeline order), G-80 (encoder SPI) |
| **3.0** | Everywhere + test/tooling reset | 4 (G-76…79), 8 (G-99…105), 9 (G-106…110) | KMP forces the `expect/actual` decisions that shape 1/2/5 |

Cross-cutting dependency rules:

- Do **not** add OTLP/Loki/ECS exporters before G-80 (byte codec SPI) and G-62
  (envelope fields) — otherwise every exporter re-invents field mapping.
- Do **not** add admin/reconfiguration before G-52 (immutable config + safe
  visibility), or runtime mutation becomes the documented race.
- KMP (G-76) must start as a design spike in 2.1: it decides clock, id, hashing,
  context and dispatcher abstractions used by Phases 1, 2, 5 and 7. Ship common
  code first, targets later.
- Phase 8 tooling (matrix DSL, mutation, conformance task) lands **alongside**
  Phase 1, not after: the matrices are how Phase 1-7 are accepted.
- Breaking changes are confined to 3.0 (facade parameter deprecations from G-64
  retire then; annotation targets from G-66 additive before then).

---

## 17. Success metrics

**Delivery truth**
- 0 uncounted losses: every drop path has a `DropReason`, counter, `onDrop` and
  (when configured) dead-letter entry — verified by mutation-killed tests.
- `flush`/`close` reports name every sink failure; no code path returns
  "success" after a failed sink flush.
- Retried delivery never duplicates for idempotent sinks; per-sink delivery
  contract documented and tested.
- 0 gap-free-sequence violations across 10⁴ kill -9 cycles; every hole produces
  a chain record.

**Performance**
- disabled/rejected path: ≤ 2 ns/op **and 0 allocations** (JMH `gc.alloc.rate.norm`).
- async admission: ≤ 1 µs/op with **16 producer threads** (today's ceiling is the
  global `admissionLock`; measure contention at 1/4/16 threads).
- buffered audit excluding sink IO: ≤ 3 µs/op; per-binding scaling within 20 %
  linear to 8 bindings.
- codec ≥ 1 M events/s/JVM for JSON, ≥ 1.5 M for CLEF/EMF targets; Room export
  ≥ 100 k rows/s with keyset paging at 10⁶ rows; remote drain ≥ 50 k events/s
  with a healthy endpoint.
- KSP added time ≤ 10 % on a 500-site module; no-op build regenerates 0 files.
- Diagnostics lane garbage: ≤ 1 allocated object per admitted non-audit event and
  **0** on a rejected one; GC garbage ≤ 5 MB/s sustained at 100 k events/s
  (measured, not estimated) — the log4j2 garbage-free gap is the yardstick.
- Shedding honesty: under every saturation scenario, 0 AUDIT events evicted while
  a sheddable event existed, in 10⁴ seeded runs.
- No budget regression > 15 % without a recorded waiver (fastest-of-three rule).

**Completeness**
- Facade parity checklist (section 4 P0/P1 rows) 100 % shipped by end of 2.5.
- Destination matrix: ≥ 5 transports × ≥ 6 encoders conformance-tested as pairs,
  with every unshipped cell listed as absent in `docs/` (no implicit gaps).
- Revision-history read-model answers are byte-identical from the Room store and
  the JSONL store on the same fixture set, at 10⁵ revisions per entity.
- Instrumentation shape matrix: ≥ 95 % legal shapes supported, the rest with an
  exact-message diagnostic and a documented workaround.
- Non-JVM targets: annotations/core/serialization/testing on JVM, Android, iOS,
  JS, WasmJS, Linux by 3.0; Room/remote/ktor at least JVM+Android+iOS.

**Trust**
- Docs/claims machine-verified (`:doc-checks` green for 30 consecutive days);
  zero drift findings open.
- Coverage: core ≥ 90 line / 85 branch; integrations ≥ 85; new PR code 100 % of
  its matrix cells; PIT mutation ≥ 80 % core.
- ~730 tests today → ≥ 3 500 by 3.0, of which ≥ 60 % are matrix/property/
  conformance-generated rather than hand-written.
- 8 named CI jobs minimum (jvm, os-matrix, android-unit, android-instrumented,
  kmp, quality-gates, perf-gates, supply-chain) with every published module
  appearing in a named job.

**Adoption**
- 5-minute quickstart still one plugin + one wiring line after Phase 2.
- A team can add named loggers, dynamic levels, ECS output and an admin endpoint
  without writing a single custom sink.
- One production-reported regression per feature area per quarter, tracked
  against the matrix cell that should have caught it (each regression must add a
  case to a matrix — the roadmap is self-amending).

---

## 18. Non-goals and constraints to preserve

- No runtime reflection in generated wrappers; compile-time proof stays the product.
- No unbounded buffers or implicit memory growth — now extended: **byte-bounded**,
  not just count-bounded (G-50).
- No audit sampling by default; audit can never be sampled without an explicit
  flag, and shed volume is always counted.
- No wholesale object serialization; metadata stays explicitly selected, adapters
  and `autoChanges` stay opt-in and provable.
- No automatic action names from method names; actions remain canonical, registry-
  checked identifiers (and now cross-module checked).
- No network behavior in core; transports stay in optional modules with SPIs.
- No framework dependencies in `ilogger-core` (markers/processors/decorators stay
  framework-free).
- No default keys, no silent crypto downgrade, and never unmask/decrypt masked
  values at the sink.
- No breaking change outside 3.0; deprecations carry a removal version note.
- Out of scope, permanently: APM/SaaS backends as *codecs-only*, distributed
  tracing implementation (integrate, do not reinvent), byte-code instrumentation
  at runtime, an IntelliJ plugin that formats code, and log aggregation/storage
  servers.

---

## 19. Definition of done (per goal)

1. Public API + KDoc, with `@since` and a stability note (experimental/stable).
2. Its declared case matrix enumerated in the test suite via the matrix DSL
   (G-103), with the cell count asserted, plus failure/edge/concurrency twins.
3. Negative/diagnostic fixtures for every compile-time rule it introduces.
4. Property or fuzz coverage for any parser/encoder/redactor it touches.
5. Conformance-suite adoption for any new sink/encoder/transport.
6. Docs: topic page + configuration reference + cheatsheet row + quickstart
   impact + `docs/24` limits update; `:doc-checks` passes.
7. Changelog entry under `Unreleased` with the behavior change contract.
8. Runnable example or `:app` screen for user-facing capability.
9. Benchmark result and allocation note for hot paths; budget gate wired.
10. `apiCheck` dump refreshed, no new mandatory transitive dependency in core,
    BOM updated, and the artifact/matrix docs regenerated.
11. Migration/deprecation note where behavior changes, with a rollback recipe.
12. Threat-model paragraph for anything touching protection, integrity or keys.

---

## 20. Open decisions

1. **Facade growth**: keep adding overloads vs. move to a builder/spec API and cut
   the 14-arg forms in 3.0 (G-64/A15). Recommendation: spec-based internally,
   overloads deprecated in 2.x, removed in 3.0.
2. **Hierarchy vs. single logger**: adopt a real tree (Logback-like) or a flat
   named-logger map with inheritance of scope only? Tree costs configuration
   complexity. Recommendation: flat map + dot-prefix nearest-ancestor inheritance.
3. **KMP scope**: everything everywhere, or core/annotations/serialization/testing
   first and Room/remote/ktor on iOS only? Recommendation: the latter; native
   IO/dispatcher model differs enough to warrant a reduced initial surface.
4. **Compiler plugin** for final classes and arbitrary functions: maintain one
   (K2 FIR/IR) or stay KSP-only with `.logged()` + manual paths? Recommendation:
   spike in 2.4, decide on measured KSP gaps; it is the biggest maintenance risk.
5. **Disruptor-style ring buffer** vs. coroutine channels for async admission.
   Recommendation: keep channels, fix the global locks (G-50); revisit only if
   the 16-thread metric misses.
6. **Processor pipeline vs. fixed stages** (G-61): a user-ordered pipeline is the
   right abstraction but breaks the "guaranteed order" guarantees the protection
   model relies on. Recommendation: pipeline with a mandatory protection segment.
7. **OTLP dependency weight**: vendored protobuf writer vs. `opentelemetry-*`
   artifacts as `compileOnly` + optional runtime. Recommendation: optional module,
   no core coupling, protobuf by default with a JSON encoder alternative.
8. **Signer/custody default**: HMAC-only, or ship Ed25519 first-class (needs
   JDK 15+/API 33 floor, Tink for older)? Recommendation: ship Ed25519 explicitly
   with a documented floor plus a Tink-backed variant.
9. **CLI language**: keep Kotlin/JVM+native, or write it in a portable language?
   Recommendation: Kotlin + GraalVM native; keep it in-repo and out of the BOM.
10. **JUnit 4 vs 5** as the shipped default test dependency of `ilogger-testing`.
    Recommendation: JUnit 5 primary, JUnit 4 compat artifact, no hard dependency.
11. **Admin transport**: JMX MBean vs in-process authenticated API + HTTP endpoint.
    Precedent matters here — Logback deleted its JMX configurator in 1.3.0 for
    security, and an audit trail that any local process can mute is not an audit
    trail. Recommendation: in-process API + HTTP as the supported surface, JMX as
    an opt-in hardened read-mostly adapter, with a CI test asserting no mutable
    MBean under default configuration.
12. **Entity revision history (G-111)**: replay events to answer "state as of T"
    (simple, unbounded cost) vs maintain a materialized revision index with
    snapshots + deltas (bounded cost, needs compaction and erasure interlock).
    Recommendation: materialized index, because examiners ask as-of questions on
    multi-year trails.
13. **Garbage-free diagnostics (G-55)** vs the shipped immutability guarantee:
    reusable records mean a `LogEvent` can be mutated after a caller retained it,
    which contradicts G-09's defensive copies. Recommendation: keep `LogEvent`
    immutable for the audit lane and confine reuse to a diagnostics-only fast path
    whose contract is "valid until the call returns" — a documented, separately
    named type (`LogRecordView`) rather than a broken promise on the real one.

---

## 21. Backlog (unprioritized)

- Typed `Category`, `EventId`, `EntityId` value classes; category registry with
  ownership metadata.
- Parquet/ORC export; ClickHouse sink; DuckDB-backed CLI query engine.
- Merkle-proof-only API (verify one record without the whole file).
- Log-based checkpoint/restore of ambient state for replay debugging.
- `@Log` on property accessors with change capture; ViewModel/`StateFlow` diffing.
- Sampled full-fidelity "tail-based" audit capture (buffer + promote on failure).
- WASM browser target with `console` writer and privacy-preserving ids.
- Localization-aware renderers without leaking locale into evidence.
- Config-as-code module (`ilogger.yaml` → builder, validated at compile time).
- FIPS-approved crypto profile; algorithm agility documentation.
- On-call/escalation hooks driven by audit events (webhook sink with signing).
- Cross-repo action registry server (organization-wide schema governance).
- Benchmarks against `j.u.l`/SLF4J/Logback on identical workloads for fairness.
- Compliance checklist doc mapping ILogger features to PCI 10 / ISO 27001 A.8.15 /
  SOC 2 CC7.2 / GDPR Art. 17 & 30 / HIPAA §164.312(b), with evidence artifacts.
- Documentation translations; a one-page "audit design" poster.

---

## Appendix A — Closed in 1.x/2.0 (history)

G-01 per-binding pipelines · G-02 delivery accounting + watermark flush · G-03 drop
reasons + dead letters · G-04 `recordBlocking`/INLINE · G-05 `CompositeLogSink.writeAll`
· G-06 rotation + fsync/time/gzip/`maxTotalBytes` · G-07 cancellation-aware
instrumentation · G-08 multi-round incremental KSP · G-09 `LogEventDraft` + defensive
copies · G-10 contextual sampling + `isLoggable` · G-11 strict mode · G-12 sink/context/
Ktor/TestKit tests · G-13 benchmark budgets + gate · G-14 backpressure helpers ·
G-15 non-blocking file IO · G-16 rate limiting + adaptive sampling · G-17 classes +
top-level functions · G-18 generics/`Result`/`Flow` + stream adapters · G-19 reserved
names · G-20 value model + adapters + domain-packages · G-21 `@LogConfig`/`@NoLog` ·
G-22 action registry + schema export · G-23 real `OMIT` + parameter-level sensitivity ·
G-24 DI-friendly extractors · G-25 Gradle plugin + BOM · G-26 diagnostics catalog +
suppression · G-28 Android module · G-29 remote HTTP sink · G-30 Ktor client+server ·
G-31 Room queries/retention/export · G-32 SLF4J/JUL bridges · G-33 OTel enricher/
metrics/spans · G-35 runtime redaction · G-36 hash-chain integrity · G-37 encryption
at rest · G-38 compliance (retention/hold/erasure) · G-39 event limits ·
G-40 TestKit assertions · G-41 fakes/conformance/property tests · G-42 CI, BCV,
Kover, Dokka, reproducible builds · G-43 CLI · G-44 documentation (40 topics).
**Deferred:** G-27 KMP (→ Phase 4 here), G-34 Spring Boot starter (replaced by
bridges + admin + OTLP as the server story). Full text of the previous revision is
in git history; per-release detail is in CHANGELOG.md.

Note on this list: several closed items are re-opened *in part* by Phase 0-1
because the register above documents gaps inside them (G-01 → D5/G-50, G-02 →
D7/G-49, G-03 → D6/G-48, G-10 → D8, G-12 → D1, G-28 → D9/I8, G-42 → D10).

## Appendix B — Sources

Standards and prior art consulted: OpenTelemetry logs data model & OTLP
(https://opentelemetry.io/docs/specs/otel/logs/data-model/,
https://opentelemetry.io/docs/specs/otlp/), Elastic Common Schema
(https://www.elastic.co/docs/reference/ecs), Loki label vs structured-metadata
guidance (https://grafana.com/docs/loki/latest/get-started/labels/), CLEF
(https://clef-json.org/), GELF
(https://go2docs.graylog.org/current/getting_in_log_data/gelf_format.html),
CloudWatch EMF
(https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Embedded_Metric_Format_Specification.html),
Spring Boot loggers endpoint
(https://docs.spring.io/spring-boot/reference/actuator/loggers.html), Log4j2
StatusLogger (https://logging.apache.org/log4j/2.x/manual/status-logger.html),
Kubernetes preStop
(https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/),
PCI DSS Requirement 10
(https://www.pcisecuritystandards.org/documents/Effective-Daily-Log-Monitoring-Guidance.pdf),
GDPR Art. 30 (https://gdpr-info.eu/art-30-gdpr/), ISO 27001 A.8.15
(https://www.upguard.com/compliance/iso-27001/8-15), RFC 3161
(https://nhimg.org/glossary/rfc-3161/), tamper-evident log practice
(https://finqub.io/learn/tamper-evident-audit-trail/,
https://mattermost.com/blog/compliance-by-design-18-tips-to-implement-tamper-proof-audit-logs/),
SLSA (https://safeguard.sh/resources/blog/supply-chain-levels-for-software-artifacts-slsa-v1),
OpenSSF Scorecard (https://github.com/ossf/scorecard), `log/slog`
(https://pkg.go.dev/log/slog), zap (https://github.com/uber-go/zap), tracing
(https://docs.rs/tracing-subscriber/latest/tracing_subscriber/), pino
(https://getpino.io/#/docs/api), structlog (https://www.structlog.org/en/stable/api.html),
loguru (https://loguru.readthedocs.io/en/stable/api/logger.html), Serilog
(https://serilog.net/, https://github.com/serilog/serilog/wiki), NLog async wrapper
(https://nlog-project.org/documentation/v6.0.0/html/T_NLog_Targets_Wrappers_AsyncTargetWrapper.htm),
swift-log (https://github.com/apple/swift-log), Kermit
(https://kermit.touchlab.co/docs/), Kotlin binary-compatibility validation
(https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html).

JVM/Android prior art consulted for the Phase 1-5 gaps (2026-11 survey): Logback
async appenders and their discarding/never-block knobs
(https://logback.qos.ch/manual/appenders.html#AsyncAppender), sifting appenders
(https://logback.qos.ch/manual/appenders.html#SiftingAppender), filters
(https://logback.qos.ch/manual/filters.html), MDC and markers
(https://logback.qos.ch/manual/mdc.html, https://logback.qos.ch/manual/markers.html),
encoders/pattern layout (https://logback.qos.ch/manual/encoders.html) and the
1.3.0 removal of JMX (https://logback.qos.ch/news.html); Log4j2 asynchronous
appenders (https://logging.apache.org/log4j/2.x/manual/async.html), garbage-free
mode (https://logging.apache.org/log4j/2.x/manual/garbagefree.html), appender
catalogue (https://logging.apache.org/log4j/2.x/manual/appenders.html),
JsonTemplateLayout (https://logging.apache.org/log4j/2.x/manual/json-template-layout.html)
and custom levels (https://logging.apache.org/log4j/2.x/manual/configuration.html#CustomLevels);
tinylog rolling policies and the shared writer (https://tinylog.org/v2/configuration/);
SLF4J MDC (https://www.slf4j.org/manual.html#mdc) with the `kotlinx-coroutines-slf4j`
bridge and the still-open context-propagation question
(https://github.com/Kotlin/kotlinx.coroutines/issues/4187,
https://github.com/micrometer/context-propagation); Timber trees and its lint
artifact (https://github.com/JakeWharton/timber); Zalando Logbook request/response
audit contract (https://github.com/zalando/logbook); entity revision history in
Hibernate Envers (https://hibernate.org/orm/envers/), Spring Data auditing
(https://docs.spring.io/spring-boot/reference/data/auditing.html) and Javers
(https://javers.org/documentation/diff-examples/); Spring Boot structured logging
defaults and the loggers endpoint
(https://docs.spring.io/spring-boot/reference/actuator/loggers.html);
kotlin-logging (https://github.com/oshai/kotlin-logging); Android on-device
inspection after Flipper's retirement — Lens, Chucker
(https://github.com/chuckerteam/chucker) and DbInspector (name-only; verify
before linking), which is why G-78 asks for a library-owned viewer rather than a
dependency on any one of them; Room multiplatform support (see the Android
multiplatform persistence guide).
