# ILogger Goals & Future Roadmap

> Status: proposal · 2026-10-21 · baseline v1.0.0 (`d3c2a99`)
>
> Scope: `ilogger-*` artifacts, the KSP processor, integrations, tooling and docs.
> This is a living plan, not a release promise. It records what v1.0.0 does,
> where it falls short, and the complete set of recommended changes to make
> ILogger cover every realistic logging/audit use case while remaining fast,
> simple, reliable and pleasant to adopt.

## 1. Objective

Make ILogger the default structured logging and audit library for Kotlin
projects:

- **Fast** — near-zero cost when disabled or filtered, sub-microsecond async
  enqueue, no allocations on rejected paths, no head-of-line blocking between
  unrelated sinks.
- **Simple** — one dependency set, one wiring line, compile-time validation
  instead of runtime configuration archaeology; useful defaults that never
  require a PhD to tune.
- **Reliable** — explicit bounded memory, no silent losses, deterministic
  flush/close, failure isolation, and audit events that survive crashes,
  retries and restarts with verifiable integrity.
- **Complete** — works on JVM, Android and Kotlin Multiplatform; covers
  interfaces, classes, top-level functions, streams, manual boundaries,
  server, mobile, CLI and offline use cases; integrates with the ecosystems
  teams already use (Room, Ktor, SLF4J, OpenTelemetry, HTTP collectors).
- **Easy for development** — forgiving annotations, actionable diagnostics,
  incremental builds, testable-by-default, copy-ready recipes and a demo that
  proves every feature.

Success is measured by adoption friction and runtime behavior, not by feature
count. Every goal below must end with tests, documentation, a changelog entry
and — for hot paths — a benchmark result.

---

## 2. Baseline: what v1.0.0 already does well

| Area | Current capability |
|---|---|
| Contracts | `@Log`, `@LogMeta`, `@LogChange`, `@LogSensitive`, `@LogExclude`, `@LogEntityId`, `@LogDao` |
| Core engine | `ILogger.create/configure/default/disabled`; `record` (suspend), `recordNow` (non-suspend), `trace..error`; immutable `LogEvent`; `Actor`; `LogContext`; correlation/causation; suppress-sources; masking/hash |
| Dispatch | `INLINE`, `ASYNC`, `BUFFERED`; bounded channel; `DROP_OLDEST`, `DROP_NEWEST`, `BLOCK`, `FAIL`; per-level `ErrorMode` (`DROP`/`RETRY`/`PROPAGATE`); bounded retry/backoff; category routing; triggers |
| Sinks | `ConsoleLogSink`, `TextFileSink` (size rotation, flush-per-write), `CompositeLogSink`, `TriggerSink`, custom-sink SPI with batch `writeAll` |
| Codegen | Interface wrappers `.logged(logger)`; entity/entity-id inference; Room-shape awareness; compile-time value grammar; sensitive policies; change tracking; START/SUCCESS/FAILURE events; extractors; `@LogDao` DB facade; optional Hilt and Koin bindings |
| Protection | Compile-time `DENY`/`MASK`/`HASH`; name-based suspicious-property warnings; `@LogExclude`; no wholesale object serialization |
| Testing | `TestKit`, `TestLogSink`, fixed clock, sequential ids; generated-code behavior tests; negative-diagnostics fixtures |
| Integrations | Room lifecycle callback + shipped `RoomLogEntity`/DAO/schema/migration + `RoomLogTarget`/`RoomLogSink` + retention; JSONL codec; Ktor server plugin + request context |
| Tooling | 5 runnable examples, full Android reference app, JMH + deterministic benchmark, 27-topic docs, CI (JVM + APK), tag-driven Maven Central/GitHub release |
| Strengths to preserve | Compile-time ambiguity errors with `Fix:` hints; no runtime reflection; audit events never silently sampled; bounded memory by design; framework-free core; excellent documentation |

---

## 3. Limitations and cons in v1.0.0

### 3.1 Runtime and delivery pipeline

| # | Limitation / con | Impact | Evidence |
|---|---|---|---|
| R1 | **One queue and one worker per logger.** All bindings share a single serial pipeline; a slow or retrying sink delays every other sink and category. | Head-of-line blocking; one bad integration degrades the whole audit trail. | [`DefaultILogger.kt:56`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L56), [`DefaultILogger.kt:451`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L451) |
| R2 | **Batch retries repeat whole groups.** A partially written batch is retried as a whole; duplicate delivery relies entirely on sink-side idempotency. | Duplicate rows/events after partial failures; expensive network retries. | [`DefaultILogger.kt:545`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L545) |
| R3 | **`flush()` is best-effort and returns nothing.** It cannot act as a barrier for events enqueued before it, and producers racing it are unsynchronized. | Tests and shutdowns cannot prove delivery; "flushed" may still lose events. | [`DefaultILogger.kt:597`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L597) |
| R4 | **`close()` has no result and does not flush bindings when no worker ever started.** It relies on each sink honoring `close()` for buffering. | Silent truncation in simple configs; no way to know whether draining succeeded. | [`DefaultILogger.kt:614`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L614), [`LogSink.kt:27`](ilogger-core/src/main/kotlin/io/ilogger/core/LogSink.kt#L27) |
| R5 | **`BLOCK` on `recordNow()` uses `trySendBlocking` without a timeout.** | A saturated queue can block a callback/main thread indefinitely (ANR risk). | [`DefaultILogger.kt:345`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L345) |
| R6 | **Asymmetric synchronous paths.** Diagnostics under `INLINE` use `runBlocking`; `recordNow(dispatch = INLINE)` throws; non-suspend code cannot get synchronous delivery. | Inconsistent semantics; no correct inline audit path for sync DAOs/callbacks. | [`DefaultILogger.kt:324`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L324), [`DefaultILogger.kt:194`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L194) |
| R7 | **No first-class delivery accounting.** Only `onDispatchError` exists; no counters for enqueued/delivered/dropped/retried, no queue depth, no per-sink latency. | Operators cannot alert on audit loss; "no data" is indistinguishable from "no events". | [`ILogger.kt:42`](ilogger-core/src/main/kotlin/io/ilogger/core/ILogger.kt#L42), [`ILogger.kt:115`](ilogger-core/src/main/kotlin/io/ilogger/core/ILogger.kt#L115) |
| R8 | **No dead-letter path.** Failed/overflowed events are reported and discarded. | Audit events that hit a transient outage are gone; no replay possible. | [`DefaultILogger.kt:435`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L435) |
| R9 | **No filter/enricher/transformer stage.** Event shape is fixed at construction; only sinks can react. | Cannot add environment/tenant/version globally, apply runtime redaction, or route by predicate without custom sinks. | [`DefaultILogger.kt:271`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L271) |
| R10 | **`CompositeLogSink` does not override `writeAll`.** Under `BUFFERED` it degrades each inner sink to per-event writes. | Loses the main batching benefit precisely where multiple destinations are used. | [`CompositeLogSink.kt:33`](ilogger-core/src/main/kotlin/io/ilogger/core/sink/CompositeLogSink.kt#L33) |
| R11 | **`TextFileSink` rotation is defective once the target file exists.** The loop never deletes `.<maxFiles>` and `Files.move` fails on an existing target; `maxFiles = 1` also fails on the second roll. | File sink can stop accepting logs or throw after rotation cycles. | [`TextFileSink.kt:86`](ilogger-core/src/main/kotlin/io/ilogger/core/sink/TextFileSink.kt#L86) |
| R12 | **File durability is limited to `BufferedWriter.flush()`** (no fsync), blocking IO runs on the single worker, and there is no time-based rotation, total-disk cap or compression. | Power-loss durability is weaker than advertised for audit; storage can grow beyond expectations. | [`TextFileSink.kt:70`](ilogger-core/src/main/kotlin/io/ilogger/core/sink/TextFileSink.kt#L70) |
| R13 | **No event size or value limits.** Manual metadata can carry arbitrary objects/strings; codecs stringify them. | Memory/DB blow-ups and accidental data leaks from manual calls. | [`JsonEventCodec.kt:118`](ilogger-serialization/src/main/kotlin/io/ilogger/serialization/JsonEventCodec.kt#L118) |

### 3.2 Event model and public API

| # | Limitation / con | Impact | Evidence |
|---|---|---|---|
| A1 | **`ILogger.record`/`recordNow` have 14 parameters.** Adding a parameter is source-breaking for every `ILogger` implementation; `DisabledILogger` duplicates the whole surface. | API evolution is expensive; fakes and decorators are tedious. | [`ILogger.kt:213`](ilogger-core/src/main/kotlin/io/ilogger/core/ILogger.kt#L213), [`ILogger.kt:305`](ilogger-core/src/main/kotlin/io/ilogger/core/ILogger.kt#L305) |
| A2 | **`LogEvent` metadata/changes are not defensively copied**; immutability is a caller convention. | Manual callers can mutate an event after dispatch; sinks/tests can observe races. | [`LogEvent.kt:9`](ilogger-core/src/main/kotlin/io/ilogger/core/LogEvent.kt#L9) |
| A3 | **No engine-side envelope fields**: sequence, received-at, duration, thread/coroutine name, process/app version. | Hard to order events from multiple producers, measure operations or enrich from environment. | [`LogEvent.kt:34`](ilogger-core/src/main/kotlin/io/ilogger/core/LogEvent.kt#L34) |
| A4 | **`LogFailure` records only the root-cause type** (no cause chain message/stack). | Production failures are often undebuggable from the audit record alone. | [`EventDetails.kt:55`](ilogger-core/src/main/kotlin/io/ilogger/core/EventDetails.kt#L55) |
| A5 | **Sampling receives a synthetic action (`log.audit`), not the real action/category/outcome**, and `isEnabled` ignores sampling. | Per-action samplers are impossible; callers cannot know whether an event will be emitted. | [`DefaultILogger.kt:295`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L295) |
| A6 | **Diagnostics are eager** (`message` and vararg pairs are computed before filtering) and carry no caller location (file/line), thread or coroutine name. | Hot-loop overhead and weak debugging ergonomics compared with mature logging facades. | [`ILogger.kt:192`](ilogger-core/src/main/kotlin/io/ilogger/core/ILogger.kt#L192) |
| A7 | **`LogContext.attributes` is `Map<String, String>` only** and the thread-local bridge is not inherited by callbacks/child threads. | Tenant/session metadata is string-only; sync callbacks silently lose coroutine context. | [`LogContext.kt:52`](ilogger-core/src/main/kotlin/io/ilogger/core/LogContext.kt#L52), [`LogContext.kt:102`](ilogger-core/src/main/kotlin/io/ilogger/core/LogContext.kt#L102) |
| A8 | **No automatic causation.** `causationId` must be supplied manually even though the engine knows the previous event in scope. | Audit chains are usually incomplete in practice. | [`LogContext.kt:44`](ilogger-core/src/main/kotlin/io/ilogger/core/LogContext.kt#L44) |
| A9 | **`Outcome` has no cancellation state**; generated `catch (Throwable)` logs cancellation as FAILURE. | Cancelled coroutines pollute failure dashboards and can trigger false alerts. | [`Codegen.kt:153`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/Codegen.kt#L153) |
| A10 | **Naming drift**: annotation `schemaVersion` vs event `actionVersion`; `category` is an untyped string. | Confusing API for new users; typos in categories are runtime-only. | [`Log.kt:104`](ilogger-annotations/src/main/kotlin/io/ilogger/annotations/Log.kt#L104), [`LogEvent.kt:48`](ilogger-core/src/main/kotlin/io/ilogger/core/LogEvent.kt#L48) |
| A11 | **No record/replay or raw `LogEvent` ingestion API**; no dedupe helper or sink decorators. | Replaying dead letters or synchronizing offline events requires custom plumbing. | [`ILogger.kt:213`](ilogger-core/src/main/kotlin/io/ilogger/core/ILogger.kt#L213) |

### 3.3 KSP and build-time experience

| # | Limitation / con | Impact | Evidence |
|---|---|---|---|
| K1 | **Only interfaces can be instrumented.** Classes, abstract/open classes, top-level functions, properties and composables are rejected. | Many real codebases use concrete repository/use-case classes; adoption stops at the boundary. | [`OpParser.kt:24`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/OpParser.kt#L24) |
| K2 | **Generic interfaces and functions are unsupported.** | Generic DAOs/repositories require manual duplication or wrappers. | [`OpParser.kt:34`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/OpParser.kt#L34) |
| K3 | **Flow-returning functions are rejected** and there are no stream adapters. | Most modern Room/repository APIs expose `Flow`; those operations cannot be audited declaratively. | [`OpParser.kt:165`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/OpParser.kt#L165) |
| K4 | **Parameter names `result`, `actor`, `context` are rejected.** | Extremely common names (especially Android `context: Context`) force renames. | [`OpParser.kt:173`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/OpParser.kt#L173) |
| K5 | **One-shot processor**: `invoked` makes the processor ignore later KSP rounds. | `@Log` symbols generated by other processors are never instrumented; multi-processor stacks break. | [`ILoggerProcessorProvider.kt:38`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/ILoggerProcessorProvider.kt#L38) |
| K6 | **Every generated wrapper depends on all files (aggregating).** | Any source change invalidates all wrappers; incremental builds are slower than necessary. | [`ILoggerProcessorProvider.kt:140`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/ILoggerProcessorProvider.kt#L140) |
| K7 | **Limited safe value types**: no `Char`, unsigned ints, `UUID`, `Instant`, `Duration`, `BigDecimal`, maps, arrays, value classes; entity ids only `Long`/`Int`/`Short`/`String`. | Common domain values require custom extractors or are rejected. | [`Ks.kt:107`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/Ks.kt#L107), [`Ks.kt:114`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/Ks.kt#L114) |
| K8 | **Entity detection uses a hardcoded package prefix list.** | Framework return types (`Result`, `Response`, `Either`, app base packages) can be mis-inferred; no configuration. | [`Ks.kt:49`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/Ks.kt#L49) |
| K9 | **Extractors must have a no-arg constructor and are instantiated per wrapper.** | No access to app services, DI, config or caches; hard to test/share. | [`OpParser.kt:714`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/OpParser.kt#L714) |
| K10 | **`SensitiveHandling.OMIT` is not implemented**; it currently behaves like `DENY`/is rejected. | Advertised policy is unusable. | [`OpParser.kt:298`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/OpParser.kt#L298) |
| K11 | **`@LogSensitive`/`@LogExclude` cannot annotate parameters or return values.** | Raw `String password` parameters are not covered by the compile-time contract. | [`Protection.kt:29`](ilogger-annotations/src/main/kotlin/io/ilogger/annotations/Protection.kt#L29) |
| K12 | **Actor override always becomes `Actor.user(id)`.** | Type/name/attributes of system/service actors are lost when selecting an id source. | [`Codegen.kt:196`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/Codegen.kt#L196) |
| K13 | **No action registry, uniqueness check or schema export.** | Duplicate actions and accidental `schemaVersion` regressions pass compilation; consumers have no machine-readable catalog. | [`OpParser.kt:66`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/OpParser.kt#L66) |
| K14 | **No class-level defaults or opt-out marker.** | Category/level/dispatch are repeated on every method; no `@NoLog` escape hatch. | [`Log.kt:94`](ilogger-annotations/src/main/kotlin/io/ilogger/annotations/Log.kt#L94) |
| K15 | **Action grammar requires a dot** and there is no `@LogAction` constant generation. | Single-segment actions are rejected; strings remain typo-prone. | [`OpParser.kt:746`](ilogger-ksp/src/main/kotlin/io/ilogger/ksp/OpParser.kt#L746) |
| K16 | **No Gradle plugin or BOM.** | Consumers hand-wire KSP, variants and versions; compile-time disable is an opaque KSP argument. | [`README.md:58`](README.md#L58) |
| K17 | **`@Log` retention is BINARY and there is no compiled metadata export.** | Schema tooling/registry cannot inspect actions from compiled outputs. | [`Log.kt:95`](ilogger-annotations/src/main/kotlin/io/ilogger/annotations/Log.kt#L95) |

### 3.4 Protection, audit integrity and compliance

| # | Limitation / con | Impact | Evidence |
|---|---|---|---|
| S1 | **Manual `record`/`recordNow` metadata bypasses compile-time protection.** Any value is accepted and later stringified. | A single manual call can leak PII/secrets that `@Log` would have rejected. | [`ILogger.kt:221`](ilogger-core/src/main/kotlin/io/ilogger/core/ILogger.kt#L221) |
| S2 | **Actor attributes are serialized wholesale by the JSON codec.** | `Actor.attributes["email"]`/tokens appear verbatim; no `@LogSensitive` equivalent. | [`JsonEventCodec.kt:50`](ilogger-serialization/src/main/kotlin/io/ilogger/serialization/JsonEventCodec.kt#L50) |
| S3 | **No runtime redaction pipeline** (regex/PII detectors, per-category policies). | Protection ends at codegen; manual and third-party events are unprotected. | [`DefaultILogger.kt:271`](ilogger-core/src/main/kotlin/io/ilogger/core/DefaultILogger.kt#L271) |
| S4 | **No tamper evidence or signatures.** | "Audit-grade" data can be edited in files/DB without detection; no chain/hash proof. | [`TextFileSink.kt:30`](ilogger-core/src/main/kotlin/io/ilogger/core/sink/TextFileSink.kt#L30) |
| S5 | **No encryption at rest or key management.** | Compliance-heavy deployments cannot use the built-in sinks for sensitive audit data. | [`TextFileSink.kt:30`](ilogger-core/src/main/kotlin/io/ilogger/core/sink/TextFileSink.kt#L30) |
| S6 | **No retention/erasure policy beyond Room day-based purge.** No per-category retention, legal hold, actor/entity erasure or deletion audit. | GDPR/CCPA and enterprise retention requirements need custom plumbing. | [`RoomLogRetention.kt:12`](ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogRetention.kt#L12) |
| S7 | **No event size caps/truncation policy.** | Oversized metadata can exhaust memory, SQLite rows or network payloads. | [`JsonEventCodec.kt:118`](ilogger-serialization/src/main/kotlin/io/ilogger/serialization/JsonEventCodec.kt#L118) |

### 3.5 Integrations and platform reach

| # | Limitation / con | Impact | Evidence |
|---|---|---|---|
| I1 | **All library modules build for JVM only.** Docs acknowledge "JVM target today"; `ThreadLocal`, `UUID`, `MessageDigest` and JVM atomics block KMP. | No iOS/JS/Wasm/native support despite a Kotlin-first positioning; Android-only consumers pay JVM assumptions. | [`ilogger-core/build.gradle.kts`](ilogger-core/build.gradle.kts), [`03-ksp-setup.md:91`](docs/03-ksp-setup.md#L91) |
| I2 | **No `ilogger-android` module**: no Logcat sink, Android lifecycle flush, crash handoff, StrictMode-safe sink, WorkManager shipping or Android actor/session provider. | Android apps must build these fundamentals themselves; the demo app does it in app code only. | [`docs/14-sinks.md:78`](docs/14-sinks.md#L78) |
| I3 | **Ktor integration is server-only and uses `recordNow` inside suspend hooks**, plus `System.currentTimeMillis()` instead of the configured clock. Under `INLINE`, `recordNow` triggers a `runBlocking` path. | No client correlation; testability suffers; potential event-loop blocking; inconsistent timestamps. | [`HttpAudit.kt:93`](ilogger-ktor/src/main/kotlin/io/ilogger/ktor/HttpAudit.kt#L93), [`HttpAudit.kt:108`](ilogger-ktor/src/main/kotlin/io/ilogger/ktor/HttpAudit.kt#L108) |
| I4 | **Ktor can emit two events for one hard failure** (`CallFailed` + `ResponseSent` 500), with no dedupe or request category. | Duplicate audit rows; downstream analytics over-count failures. | [`24-limits-and-constraints.md:49`](docs/24-limits-and-constraints.md#L49) |
| I5 | **Room query surface is minimal**: only `recent(limit)` and `purgeBefore`; no Flow, filters, paging, export. `RoomLogTarget` waits for `bind` indefinitely. | Apps cannot build audit viewers/export without custom DAOs; a forgotten bind blocks shutdown until timeout. | [`RoomLogDao.kt:17`](ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogDao.kt#L17), [`RoomLogTarget.kt:65`](ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogTarget.kt#L65) |
| I6 | **Retention only sweeps after a successful insert.** | An idle app never purges; storage can exceed the intended window. | [`RoomLogTarget.kt:68`](ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogTarget.kt#L68) |
| I7 | **JSON codec is encode-only**, degrades nested values to `toString()`, and has no explicit NaN/Infinity policy. | No round-trip, no read-back from Room payloads, and potentially non-JSON scalar output. | [`JsonEventCodec.kt:34`](ilogger-serialization/src/main/kotlin/io/ilogger/serialization/JsonEventCodec.kt#L34) |
| I8 | **No remote/HTTP sink** (batching, auth, gzip, offline spool, circuit breaker). | Centralized log collection requires a bespoke sink every time; the core wisely ships no transport. | [`24-limits-and-constraints.md:9`](docs/24-limits-and-constraints.md#L9) |
| I9 | **No SLF4J / java.util.logging / Logback / kotlin-logging bridges.** | Existing JVM apps cannot adopt gradually or route third-party logs into ILogger. | — |
| I10 | **No OpenTelemetry/Micrometer integration.** | Trace/span correlation and operational metrics must be wired by hand. | — |
| I11 | **No Spring Boot starter or graceful-shutdown auto-configuration.** | Server adoption is more manual than competing logging libraries. | — |
| I12 | **No log viewer/CLI/export tooling.** | Reading JSONL/Room audit data requires custom scripts; verification/replay is out of reach. | — |

### 3.6 Testing, tooling and CI

| # | Limitation / con | Impact | Evidence |
|---|---|---|---|
| T1 | **Coverage gaps**: no tests for `ConsoleLogSink`, `TextFileSink`, `CompositeLogSink`, `LogContexts`, or the Ktor plugin; `ilogger-testing` itself is untested. | Regressions like the rotation bug are invisible; integrations are shipped unverified. | [`ilogger-ktor/src`](ilogger-ktor/src), [`ilogger-core/src/test`](ilogger-core/src/test) |
| T2 | **`TestLogSink.awaitCount` polls real time** (5 ms sleeps); no assertion DSL, predicate awaits or virtual-time draining. | Async tests are slower and flaky; assertions are verbose. | [`TestLogSink.kt:47`](ilogger-testing/src/main/kotlin/io/ilogger/testing/TestLogSink.kt#L47) |
| T3 | **No fakes/golden fixtures** for file/Room/HTTP sinks; no chaos/failing-sink harness beyond ad-hoc test classes. | Hard to test error modes, backpressure and retention in consumer apps. | — |
| T4 | **CI is Ubuntu/JDK 17 only; no Windows/macOS JVM matrix, no Android instrumented tests, no coverage gate, no benchmark regression gate, no API binary-compatibility check, no Dokka publication.** | Platform-specific defects (file rotation, path handling) and perf/API regressions ship undetected. | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| T5 | **No property/fuzz tests** for the JSON codec, value parser or KSP expression grammar. | Edge-case crashes and silent data corruption are possible. | — |
| T6 | **No benchmark for generated wrapper overhead or sink batching paths.** | Optimization claims cover only manual APIs and a nop sink. | [`25-performance-and-benchmark.md:14`](docs/25-performance-and-benchmark.md#L14) |
| T7 | **`@Log` action and schema contracts are not validated across modules.** | Duplicate/incompatible actions and schema evolution mistakes reach production. | — |

---

## 4. Goal-setting principles

1. **Preserve the audit contract.** Success events after success, failure events with the original exception intact, no audit sampling by default, bounded memory.
2. **Additive first, breaking only when justified.** Keep v1 APIs working through v1.x; reserve a small, well-documented v2 for KMP + API-shape changes.
3. **Defaults must be safe.** New features (redaction, signing, remote shipping, strict validation) are opt-in unless their absence is a correctness bug.
4. **One concept, one way.** Prefer extending existing SPIs over parallel abstractions; every new module must justify its dependency and maintenance cost.
5. **Observability is a feature.** Every drop, retry, timeout and backpressure event must be countable and attributable.
6. **Developer time is a budget.** KSP incremental builds, actionable diagnostics, one-line setup and copy-ready recipes are first-class requirements.

---

## 5. Prioritized goals

Priority: **P0** = correctness/reliability blockers; **P1** = required for broad, comfortable adoption; **P2** = advanced/enterprise/ecosystem. 
Effort: **S** ≤ 1 week, **M** ≤ 1 month, **L** > 1 month (single maintainer estimate).

### 5.1 P0 — Correctness and reliability hardening

#### G-01 — Isolate sinks with per-binding pipelines
- **Status:** ✅ Implemented (2026-10-21) — per-binding bounded queues/workers, `sinkConcurrency`, aggregate flush/close; covered by `PipelineIsolationTest`.
- **Problem:** one worker and one queue serialize every binding; a slow sink blocks all audit delivery (R1, R2).
- **Work:** route each event to independent bounded per-binding queues with one worker per binding (configurable `sinkConcurrency`, default 1); preserve per-sink ordering; keep a shared admission queue only if needed; make `flush`/`close` aggregate across pipelines; expose per-sink states.
- **Acceptance:** a 1 s-blocking sink cannot delay a fast sink; retries in one binding do not stall others; memory stays bounded per binding; tests for slow/failing/cancelled sinks.

#### G-02 — Delivery accounting, true flush barrier and close report
- **Status:** ✅ Implemented (2026-10-21) — watermark `flush(timeout)`, `awaitIdle`, `stats`, `closeAndReport`, `FlushReport`/`CloseReport`/`DeliveryStats`; covered by `DeliveryAccountingTest`.
- **Problem:** `flush()` and `close()` cannot prove delivery or report losses (R3, R4, R7).
- **Work:** add per-logger counters and `FlushReport`/`CloseReport`; `flush(timeout)` waits for events enqueued before the call via a sequence watermark; `close()` always flushes every distinct sink before closing; add `awaitIdle(timeout)`/`drain()` for tests; expose `queueDepth`, `inFlight`, `delivered`, `droppedByReason`.
- **Acceptance:** no silent drops on graceful shutdown; report enumerates pending/dead-lettered events; race-free tests with concurrent producers.

#### G-03 — Explicit drop reasons and optional dead-letter queue
- **Status:** ✅ Implemented (2026-10-21) — `DropReason`/`DroppedEvent`, `onDrop`, bounded background `DeadLetterSink`; covered by `DropReportingTest` (durability depends on the configured sink).
- **Problem:** overflow/retry/close failures vanish after a callback (R8).
- **Work:** introduce `DropReason` (`OVERFLOW_OLDEST`, `OVERFLOW_NEWEST`, `CLOSED`, `RETRY_EXHAUSTED`, `SINK_FAILURE`, `SAMPLED`, `SUPPRESSED`, `STRICT_REJECT`, `MISSING_BINDING`); add `onDrop(event, reason, error)` and an optional `DeadLetterSink` (file/Room/memory); guarantee every lost event is counted.
- **Acceptance:** all loss paths emit exactly one reason; dead-letter survives process restart when configured; tests for each reason.

#### G-04 — Safe synchronous/non-suspend recording
- **Status:** ✅ Implemented (2026-10-21) — `recordBlocking(timeoutMillis)`, bounded INLINE `recordNow`, timeout-aware BLOCK admission, non-blocking diagnostic path + `DirectLogSink` fast path; covered by `BlockingRecordingTest`.
- **Problem:** `recordNow` cannot do INLINE; `BLOCK` can hang threads; diagnostics use `runBlocking` (R5, R6).
- **Work:** add a non-suspend `recordBlocking(...)` with an explicit timeout and no coroutine requirement; permit bounded inline delivery for sync code; replace unbounded `trySendBlocking` with timeout-aware admission; give diagnostics a non-blocking inline fast path; document thread/blocking contracts.
- **Acceptance:** saturated queue never blocks a test thread beyond the configured limit; sync DAO/callback can produce a durable event; tests with a stalled sink and a full queue.

#### G-05 — Fix batching in `CompositeLogSink`
- **Status:** ✅ Implemented (2026-10-21) — `writeAll` forwards one batch per inner sink with error isolation; covered by `CompositeLogSinkTest`.
- **Problem:** batch dispatch degrades to per-event writes (R10).
- **Work:** override `writeAll` to forward the whole batch to each inner sink; preserve `BEST_EFFORT`/`FAIL_FAST` semantics; add per-sink error isolation tests.
- **Acceptance:** one `writeAll` call per inner sink per engine batch.

#### G-06 — Harden and extend `TextFileSink`
- **Problem:** rotation deletes/fails incorrectly; no fsync/time rotation/disk cap; blocking IO on worker (R11, R12).
- **Work:** fix rotation with `REPLACE_EXISTING`/target deletion and correct `maxFiles` bounds; add tests for `maxFiles = 1, 2, 5` and >2× rotations; add `fsync` option, time-based rotation, total-size cap, optional gzip archives, and an IO dispatcher option; flush on `close()`; make errors actionable.
- **Acceptance:** Windows/Linux tests prove rotation/retention; no exception at any roll count; graceful close retains all accepted events; documented durability levels (buffered/os/fsync).
- **Status:** ✅ Implemented (2026-10-21) — correct rotation at all `maxFiles` values, `fsyncEveryWrite`, `ioDispatcher`, lazy `maxAgeMillis`, `gzip` archives, `maxTotalBytes` pruning; covered by 13 `TextFileSinkTest` cases; docs 14 + CHANGELOG updated.

#### G-07 — Cancellation-aware instrumentation
- **Problem:** cancellation is logged as a failure and can mask/duplicate control flow (A9).
- **Work:** catch `CancellationException` separately in generated wrappers; default `@Log(onCancellation = SKIP)` while still rethrowing the original exception; add `LogFailure.cancelled`/`isCancellation` (or a v2 `Outcome.CANCELLED`); never let a logging failure replace cancellation.
- **Acceptance:** cancelling a wrapped suspend call emits no FAILURE by default; identity and cancellation semantics are preserved; explicit opt-in emits a marked cancellation event.
- **Status:** ✅ Implemented (2026-10-21) — `OnCancellation.SKIP|LOG`, `LogFailure.cancelled`/`isCancellation`, identity-preserving rethrow, logging errors attached as suppressed; three cancellation tests in `CodegenBehaviourTest`.

#### G-08 — Make KSP multi-round and incremental-correct
- **Problem:** the processor runs only once and invalidates everything (K5, K6).
- **Work:** remove the one-shot guard; return deferred symbols for types that are not yet resolvable; process symbols in later rounds; use isolating `Dependencies(containingFile)` for interface wrappers and aggregating dependencies only for database facades; add integration tests with a second processor generating `@Log` interfaces and an incremental no-op build test.
- **Acceptance:** generated sources from other processors are instrumented; changing one file regenerates only its wrapper; no-op builds generate nothing new; build time improvement is measured.
- **Status:** ✅ Implemented (2026-10-21) — multi-round processor with deferred symbols, deduped diagnostics, isolating wrapper dependencies, aggregating facades; `:ksp-tests-generator` proves second-processor generation. No-op builds are up-to-date (measured gain deferred to G-13's benchmark gate).

#### G-09 — Defensive event construction and a stable recording API
- **Problem:** the 14-argument API is hard to extend and `LogEvent` trusts mutable caller data (A1, A2).
- **Work:** introduce `LogEventDraft`/`EventSpec` + `ILogger.record(spec)`; copy metadata/changes into immutable containers; validate values in strict mode; keep the existing methods as deprecated-but-working overloads for v1.x; use the new shape for replay/dedupe helpers.
- **Acceptance:** manual event construction cannot be mutated after record; adding future fields does not break implementors of `ILogger`; fakes become one-liners.
- **Status:** ✅ Implemented (2026-10-21) — `LogEventDraft` with draft overloads on `ILogger`; `LogEvent` copies `metadata`/`changes` into unmodifiable containers (and gained an explicit `copy` after leaving data-class-generated members). `LogEventDraftTest` covers field preservation, draft validation, post-record mutation, and draft-only custom loggers.

#### G-10 — Correct sampling + visibility
- **Problem:** samplers never see the real action/category, and callers cannot predict sampling (A5).
- **Work:** expand the sampler SPI (overload or new `SamplingContext`) with action, category, outcome and ambient attributes; add `ActionSampler`, `CategorySampler`, `RateLimitSampler`; sample a whole operation (START/SUCCESS/FAILURE) consistently; add `isLoggable(level, action, category)`; keep audit unsampled by default.
- **Acceptance:** tests prove real action/category reach the sampler; correlated events share a decision; sampler tests are deterministic with an injected RNG.
- **Status:** ✅ Implemented (2026-10-21) — `SamplingContext` + `ContextualSampler`, `ActionSampler`/`CategorySampler`/`RateLimitSampler`, legacy `LogSampler` adapter (now receives the real action), per-correlation decision cache (bounded, keyed by correlation id + action), `ILogger.isLoggable`, `auditSampling` gate. `SamplingTest` covers all of the above plus boundary rates and injected `Random`.

#### G-11 — Strict runtime validation for manual events
- **Problem:** manual metadata can contain objects, secrets and oversized values (A2, R13, S1).
- **Work:** add `strictMode = OFF|WARN|THROW` (debug default WARN); validate log-safe value types, max event bytes, max metadata entries/string length, actor attribute keys; report `STRICT_REJECT`; provide `LogSafeValue` wrappers for explicit opt-in.
- **Acceptance:** strict mode catches domain objects/oversized payloads before sinks; zero cost when OFF; tests for every value category.
- **Status:** ✅ Implemented (2026-10-21) — `StrictMode`, `LogSafeValue`, `StrictValidation` (safe types, nested collections, key/value/actor/change checks, entry and byte budgets), `STRICT_REJECT` drops with stats, builder limits, debug-build `WARN` wiring in `:app`. `StrictModeTest` covers OFF/WARN/THROW across `record`/`recordNow`/`recordBlocking`, every rejection class, and the opt-in wrapper.

#### G-12 — Fill critical test gaps
- **Problem:** built-in sinks, context, Ktor and TestKit are untested (T1).
- **Work:** unit tests for console/text/composite sinks, rotation, context merging/thread bridge, Ktor hooks (including duplicate-event behavior), test kit; add failing/chaos sink fixtures and concurrency stress tests; run them in CI.
- **Acceptance:** coverage targets met (core ≥ 90%, integrations ≥ 80%); no new feature merges without tests for golden and failure paths.
- **Status:** ✅ Implemented (2026-10-21) — `ConsoleLogSinkTest`, `LogContextsTest`, `ChaosSinkTest` (failing/close-failing/reentrant/concurrent), `HttpAuditTest` (success, generated correlation, exclusions, single failure event, context propagation, injected clock, `http` category), `TestKitTest`; TextFileSink and CompositeLogSink were already covered. Test-driven fixes: `@LogDao(enabled = false)` accessors now pass through the generated facade unwrapped.

### 5.2 P1 — Speed, scale and operations

#### G-13 — Performance budgets and a regression gate
- **Problem:** only four manual paths are benchmarked; no wrapper/sink/batch coverage (T6).
- **Work:** define budgets: disabled ≤ 2 ns/op; filtered diagnostic zero allocations; async enqueue ≤ 1 µs/op; buffered audit excluding sink ≤ 3 µs/op; add JMH cases for generated wrappers, metadata extraction, batching, per-sink pipelines, file sink; fail CI on >15% regression (with noise handling).
- **Acceptance:** benchmarks published per release; regression gate documented and enforced.
- **Status:** ✅ Implemented (2026-10-21) — JMH extended to ten cases (disabled, filtered, direct-sink inline, audit inline, async/buffered admission, two-pipeline fan-out, generated wrapper dispatch and metadata extraction, rotating file sink). `Budgets` + env-gated `BudgetGateTest` (median, baseline subtraction, 15% tolerance) enforce 2 ns / 1 µs / 3 µs; the Linux `Benchmark — producer budgets` CI job runs it. Profiling the gate drove one engine fix: the default `UuidEventIdGenerator` no longer uses shared `SecureRandom` (async enqueue 4.2 µs → 1.0 µs on the reference host). Numbers documented in `docs/25-performance-and-benchmark.md`.

#### G-14 — Backpressure-aware producer helpers
- **Problem:** producers cannot check capacity or record opportunistically without triggering policies (R5, R7).
- **Work:** add `tryRecord`, `recordOrDrop`, `isBacklogged`, `availableCapacity`, per-binding `queueDepth`; expose a cheap `hasSinks`/`isLoggable` pre-check; document patterns for high-volume producers.
- **Acceptance:** producers can shed diagnostic load without exceptions; tests cover all overflow policies.
- **Status:** ✅ Implemented (2026-10-21) — `tryRecord`/`recordOrDrop` (non-blocking, non-throwing; strict rejects and inline non-direct sinks return `false`), `isBacklogged`/`availableCapacity`/`hasSinks`, and `BindingStats` per binding in `DeliveryStats`. `BackpressureHelpersTest` covers disabled/closed, BLOCK/FAIL non-blocking drops, DROP_OLDEST eviction vs DROP_NEWEST rejection, INLINE direct vs blocking sinks, strict THROW, and per-binding counters. Documented in `docs/16-dispatch-backpressure-error-modes.md`.

#### G-15 — Async/non-blocking file and sink IO
- **Problem:** blocking IO on a single worker (R12).
- **Work:** dedicated IO dispatcher per file sink; buffered writer with configurable flush policy; optional async random-access channel; never run blocking IO on `Dispatchers.Default`; document fsync trade-offs.
- **Acceptance:** file sink does not block the engine worker; throughput benchmark and durability tests.
- **Status:** ✅ Implemented (2026-10-21) — suspend `write`/`flush` always hop to a per-instance `Dispatchers.IO.limitedParallelism(1, "ilogger-file-io")` view (never `Dispatchers.Default`, never concurrent per sink); `writeDirect`/`recordBlocking` keep an explicit synchronous contract. Buffered writer + `flushEveryWrite`/`fsyncEveryWrite` durability levels were already in place. `TextFileSinkTest` proves off-caller execution and serialization under concurrent writers; deterministic harness case 5 measures enqueue + drain at 1,531 ns/event (~653k events/s). Documented in `docs/14-sinks.md` and `docs/25-performance-and-benchmark.md`.

#### G-16 — Rate limiting and adaptive sampling
- **Problem:** sampling is static per level; no protection against chatty actions/categories.
- **Work:** token-bucket limiter per level/action/category/actor; adaptive sampling that sheds diagnostics first, never audit; deterministic test RNG; metrics for skipped volume.
- **Acceptance:** a 100k/s diagnostic flood is bounded while audit flow is unaffected; tests measure shed counts.
- **Status:** ✅ Implemented (2026-10-23) — `TokenBucketRateLimiter` keys independent buckets by `RateLimitScope` (GLOBAL/LEVEL/ACTION/CATEGORY/ACTOR), refills at `permitsPerSecond` up to `burst`, bounds its key space with LRU eviction (`maxBuckets`) and takes an injectable `LogClock`. `AdaptiveSampler` measures the offered diagnostic volume per window, sets admission probability to `target/observed` (clamped to `minRate`) and enforces a hard token-bucket ceiling from the first window, recovering to full admission after a quiet window; its `Random` is injectable for deterministic tests. `CompositeSampler` requires every delegate to accept. Both limiters return `true` for AUDIT without counting or consuming tokens — audit flow is structurally unaffected (even with `auditSampling = true`). `SamplingContext` gained the resolved `actor`, populated by the engine for diagnostics and `isLoggable`; `SamplingStats` exposes considered/sampled/skipped, live and observed rates and bucket counts, while `DeliveryStats.droppedByReason[SAMPLED]` remains the engine-level metric. `RateLimitingTest` (10 tests) proves a simulated 100k events/s flood admits only burst + one second of refill (~2k) while every interleaved audit event passes, per-scope keying, LRU eviction, seeded reproducibility and end-to-end engine integration. Docs 17 (rate limiting, adaptive sampling, composition, metrics) and 26 updated.

### 5.3 P1 — Developer experience and codegen reach

#### G-17 — Instrument classes, abstract/open classes and top-level functions
- **Problem:** interface-only support blocks many real codebases (K1).
- **Work:** generate subclassing wrappers for `open`/`abstract` classes; generate function adapters for top-level functions; offer an optional compiler plugin (FIR/IR) for final classes, constructors and arbitrary functions; keep interfaces the recommended path.
- **Acceptance:** examples compile for interface, abstract class, open class, top-level function and (plugin path) final class; diagnostics remain actionable.
- **Status:** ✅ Implemented (2026-10-22) — `@Log` on open/abstract class members generates an `internal` subclass wrapper that forwards every superclass constructor parameter (varargs spread) and overrides open members via `super`; top-level plain functions get a public `<Name>_ILogger` adapter object and extension functions keep their receiver with the logger as first parameter (logger-first extensions). Final classes, `INNER` classes, private primary constructors and abstract members produce actionable diagnostics (subclassing has no instance proxy and no `super` implementation). A compiler plugin for final classes is deferred (G-17b, v2.0). Behaviour tests cover open class, abstract class, top-level plain and extension shapes.

#### G-18 — Generics, nullable results and stream support
- **Problem:** generic signatures and `Flow`/`Channel` are rejected (K2, K3).
- **Work:** support generic interface/function type parameters in generated wrappers; unwrap `Result<T>`/nullable returns for entity/id inference; ship `loggedFlow`/`loggedChannel`/suspend-sequence adapters with explicit subscription/error/completion events; optionally allow `@Log` on Flow with documented semantics (start + terminal only).
- **Acceptance:** generics tests compile and emit correct events; Flow adapter tests cover normal completion, error and cancellation.
- **Status:** ✅ Implemented (2026-10-22) — generic interfaces/classes/functions generate typed wrappers (type variables with bounds preserved through the wrapper class, subclass and `logged()` extension; a type parameter is never inferred as a domain entity). `Result<T>`/nullable returns unwrap for entity inference and result chains (`entityId = "result.id"` on `Result<Task>` renders `result.getOrNull()?.id?.toString()`). `@Log` on Flow-returning functions instruments the stream lifecycle: START at subscription, SUCCESS on normal completion, FAILURE on stream error (dispatch failures suppressed, error rethrown verbatim), cancellation SKIPPED by default or recorded as a cancelled FAILURE via `OnCancellation.LOG` through `recordNow`+ASYNC. `result.*` selections on flows are rejected with a pointed diagnostic. Core ships `loggedFlow`/`loggedChannel`/`loggedSequence` adapters with the same semantics and disabled-logger fast paths. Behaviour tests cover generics, Result/nullable unwrap, flow completion/error/cancellation; core StreamAdaptersTest covers the adapters.

#### G-19 — Remove reserved-name friction
- **Problem:** `context`/`actor`/`result` parameters are rejected (K4).
- **Work:** resolve source expressions by parameter index and emit collision-free internal locals; only reject an expression that is genuinely ambiguous; add diagnostics with disambiguation syntax if needed.
- **Acceptance:** functions with `context: Context`, `actor: String`, `result: T` compile; existing source grammar is unchanged.
- **Status:** ✅ Implemented (2026-10-22) — the reserved-name rejection was removed; source expressions still resolve `result`/`actor`/`context` heads specially, parameters resolve by index, and generated locals are renamed (`iloggerCtx`, `iloggerResult`, `iloggerError`, ...) until free of parameter names while wrapper state is read through `this.`. `ReservedNamesRepository` (parameters named `result`, `iloggerResult`, `context`, `actor`, `logger`, `iloggerDelegate`, `error`) compiles and records with entity-id/metadata selection intact.

#### G-20 — Expand the safe value model and value adapters
- **Problem:** common types are rejected (K7, K8).
- **Work:** add `Char`, `UInt`, `ULong`, `UUID`, `Instant`, `Duration`, `BigDecimal`, arrays, one-level maps and value classes; add configurable `ValueAdapter<T>` registry (global + per annotation) with tested canonical JSON forms; add `ilogger.domain-packages` KSP option for entity detection; support `Result<T>` unwrapping.
- **Acceptance:** all listed types compile and produce deterministic JSON; custom adapters work in wrappers and manual recording; docs enumerate supported shapes.
- **Status:** ✅ Implemented (2026-10-22) — `Ks.isLoggableValueType` accepts Char, unsigned integers, UUID, Instant, Duration, BigDecimal/BigInteger, arrays (incl. primitive arrays), one-level lists/maps with loggable element types, value classes over loggable underlying types and `Result<T>`; value classes and type parameters are never entity candidates. `LogMeta(adapter = ...)` instantiates a `ValueAdapter` once per wrapper (mutually exclusive with `extractor` and with MASK/HASH sensitivity, validated like extractors); core ships `ValueAdapter` + `ValueAdapterRegistry` wired through `ILoggerBuilder.valueAdapter` and applied at event materialization to metadata and changes (exact-runtime-class lookup, empty-registry pass-through, throwing adapters degrade to null). `ilogger.domain-packages` restricts entity inference to configured prefixes (default: blacklist-only). `JsonEventCodec` renders every new type canonically with a depth guard; fixtures + behaviour tests cover all shapes (`ValueModelRepo`, `ValueAdapterRegistryTest`, JSON canonical-forms test). Docs: 08 (value model), 10 (adapters), 15 (JSON forms), 03 (option).

#### G-21 — Class-level defaults and opt-outs
- **Problem:** annotations repeat category/level/dispatch per method (K14).
- **Work:** add `@LogConfig`/`@LogDefaults` on interfaces/classes for category, level, dispatch, sensitive policy and sampling; add `@NoLog` to exclude a method; method annotations override defaults; validate conflicts.
- **Acceptance:** a class-wide default applies to all annotated methods; per-method overrides win; diagnostics are tested.
- **Status:** ✅ Implemented (2026-10-22) — `@LogConfig(category, level, dispatch, sampling, sensitive)` on interfaces and open/abstract classes provides defaults for `@Log` members that leave a field at its own sentinel (`level = AUDIT`, `category = ""`, `dispatch = DEFAULT`, `sampling = -1.0`); explicit member values win and `@Log(useClassDefaults = false)` pins a member to its literal values (AUDIT survives a non-AUDIT config). `sensitive = MASK|HASH` upgrades policy-less `LogMeta` selections of `@LogSensitive` values to that policy (converter-bearing entries keep their own shape; entity ids and changes still refuse); `OMIT` rejected at the container. `@NoLog` on a function excludes it from every wrapper (`@NoLog` wins over `@Log`). Invalid `@LogConfig` values report container-scoped diagnostics; the audit-sampling rule evaluates the resolved level/sampling. Fixtures `ConfiguredRepo` (inherit/override/pin/`@NoLog`) and `MaskingConfigRepo` (class MASK policy) with three behaviour tests; docs 04 (annotation reference section) and 09 (policy fallback) updated.

#### G-22 — Action registry, duplicate detection and schema export
- **Problem:** actions are unchecked strings with no machine-readable catalog (K13, T7).
- **Work:** add `@LogAction`/`@LogSchema` contracts or a small DSL that generates constants; validate action format, duplicates, `schemaVersion` monotonicity and required metadata; generate a manifest/JSON schema in the build (`iloggerSchema` task); expose it to the KSP diagnostics.
- **Acceptance:** duplicate actions fail/warn in CI; version bumps without schema changes are detectable; consumers can diff generated schemas across releases.
- **Status:** ✅ Implemented (2026-10-22) — `@LogActions(...)` on a constants object declares the canonical codes (annotation arguments, since KSP cannot read `const val` initializers) and generates `<Name>_ILoggerActions.actions` (sorted, deduplicated). Every parsed operation folds into a deterministic `META-INF/ilogger/schema.json` written from `finish()` with aggregating dependencies: `(action, schemaVersion)` → `schemaId`, entity, metadata keys, change fields, sorted `sources`; `declared` mirrors the registry. Same action+version with a different shape is a build error; an identical shape under a different version, an undeclared action (when a registry exists) and a single-segment code warn; `@LogActions` on a non-object or malformed codes error. `ilogger.schema-output` writes an extra copy for VCS/CI diffs. Fixtures cover all four diagnostics; `SchemaExportTest` asserts the registry, manifest ordering, multi-source folding, the declared list and the copy; docs 03 (option) and 06 (registry and manifest) updated.

#### G-23 — Real `OMIT` and richer sensitivity contracts
- **Problem:** `OMIT` is unusable and parameters cannot be marked sensitive (K10, K11).
- **Work:** implement `OMIT` as “do not emit this entry” (or forbid the selection consistently and document it); add `@LogSensitive`/`@LogExclude` targets for value parameters, receivers and return types; sanitize actor attributes via policy; generate no entry for omitted constants.
- **Acceptance:** OMIT produces no key and no allocation; parameter-level contracts are enforced; tests cover metadata/id/changes paths.
- **Status:** ✅ Implemented (2026-10-22) — `SensitiveHandling.OMIT` now means "emit no entry": an explicit `LogMeta(..., sensitive = OMIT)` generates no key, converter or allocation, and `@LogConfig(sensitive = OMIT)` drops policy-less selections that touch sensitive values while keeping the rest. Actor overrides are sanitized by the container policy (MASK/HASH render a projection; OMIT drops the actor; DENY errors); entity-id (ID) and `LogChange` (CHANGE) sources still hard-refuse sensitive fields. `@LogSensitive`/`@LogExclude` now target classes, properties, fields, value parameters and type uses, and roots of chains are enforced (annotated parameters, receiver, `@LogSensitive` return types). Extractor/adapter + OMIT is rejected. Tests: `SensitiveContractsTest` (6 behaviour tests) plus `ParamSensitiveDeny`, `ParamSensitiveId`, `SensitiveChange`, `ReturnSensitiveDeny` and `ExcludedParam` diagnostics; docs 04 and 09 updated.

#### G-24 — DI-friendly extractors
- **Problem:** extractors are no-arg and instantiated per wrapper (K9).
- **Work:** add an `ExtractorRegistry`/factory SPI resolved once per logger, optional Hilt/Koin/manual registration, caching and thread-safety guarantees; validate runtime output types; allow suspend extractors only if the cost is documented.
- **Acceptance:** extractors can use app services/config; instances are reused; output validation and tests.
- **Status:** ✅ Implemented (2026-10-22) — annotation-named extractors resolve through a per-logger `ExtractorRegistry` (lazy, thread-safe, exactly one instance under concurrent first use; factories run outside registry locks; late registration is rejected) instead of per-wrapper construction. `LogMeta(..., inject = true)` requires `ILogger.create { extractor(Type::class) { ... } }` (factory or instance) and fails loudly at first use otherwise; classes without `inject` keep a no-arg/object fallback cached in the registry, and a type used both ways resolves once with inject winning. Generated fields call `logger.extractors.get/require`; top-level wrappers resolve inline. Every call goes through `validated()`: throwable or non-loggable output becomes a `null` metadata value (key present), always on and independent of `StrictMode`, using the expanded log-safe model at depth 4. Suspend extractors are deliberately unsupported (extraction is synchronous on the caller's path). Constructor/inject contract violations are compile errors. Tests: `ExtractorRegistryTest` (13), `ExtractorDiTest` (5 end-to-end wrapper tests), plus two diagnostics fixtures; docs 04, 09, 10 and 26 updated.

#### G-25 — Gradle plugin and BOM
- **Problem:** setup and versioning are manual (K16).
- **Work:** publish `ilogger-gradle-plugin` applying KSP, adding dependencies, wiring options (`enabled`, `strict`, `domainPackages`, diagnostics), variant-aware compile-time disabling, and `ilogger-bom`; document a three-line setup for Android/JVM/KMP.
- **Acceptance:** a fresh sample project is instrumented with one plugin declaration; options are type-safe; CI verifies published plugin behavior.
- **Status:** ✅ Implemented (2026-10-22) — `ilogger-gradle-plugin` (included build, published as `io.github.mohamed-dev-ui.ilogger`) applies KSP, injects core/annotations/processor at the plugin's own generated version, and forwards the typed `ilogger { }` DSL (`enabled`, `strict`, `verbose`, `domainPackages`, `schemaOutput`, `disabledVariants`). Variant disabling adds a task-level argument provider so only ILogger's processor is off for that KSP task; JVM and Android plugin ids are supported, KMP remains manual until G-27. KSP gained `ilogger.strict`, which promotes processor warnings to errors. `ilogger-bom` (`java-platform`) constrains all seven published modules. `examples/gradle-plugin` is a fresh consumer proving one-line setup end-to-end, and plugin TestKit tests assert option forwarding, defaults and variant matching. Docs updated: 02, 03, README; CI/release build and publish the plugin + BOM.

#### G-26 — Better KSP diagnostics and IDE support
- **Problem:** rules are strict but not always discoverable; IDE integration is absent.
- **Work:** add warnings for duplicate actions, missing actor, sensitive actor attributes, unused `@LogDao`, unbound sinks; provide `Fix:` text for every diagnostic; support suppression; publish a diagnostics catalog generation test; integrate with the Gradle plugin for readable build output.
- **Acceptance:** every new rule has positive/negative fixtures; diagnostics tests assert messages verbatim; quick fixes documented.
- **Status:** ✅ Implemented (2026-10-22) — every diagnostic has a stable `ILGxxx` code, severity, summary and `Fix:` text in `Diagnostic`; `docs/23-diagnostics-catalog.md` is the public table and `DiagnosticCatalogTest` fails the build if a code, severity or summary drifts. New warnings: duplicate action declarations/emissions (`ILG062`), undeclared actions (`ILG063`), sensitive actor attributes (`ILG019`), unused `@LogDao` (`ILG053`), plus opt-in `ilogger.require-actor` (`ILG020`) and `ilogger.sink-categories` (`ILG021`). Suppression works per symbol/file via `@Suppress("ILGxxx")` (enclosing declarations, `ILG`, `ALL`) and module-wide via `ilogger.suppress`; the Gradle DSL gained `suppress`, `requireActor`, `sinkCategories`, and `./gradlew iloggerDiagnostics` prints the catalog from a resource generated out of the verified doc. `:ksp-diagnostics-warnings` and `:ksp-diagnostics-options` cover positive, negative, suppressed and compliant fixtures with verbatim assertions.

### 5.4 P1 — Platform and integration coverage

#### G-27 — Kotlin Multiplatform artifacts
- **Problem:** JVM-only internals block KMP/iOS/JS (I1).
- **Work:** split core into common + platform source sets; `expect/actual` for clock, UUID/ULID, SHA-256 hashing, thread-local context, atomic primitives and dispatchers; keep Android/JVM specifics in optional modules; publish JVM, Android, iOS/Native, JS/Wasm targets for annotations, core, testing, serialization; decide Room's KMP target support.
- **Acceptance:** a KMP sample uses `@Log`, `TestKit` and JSON on JVM + iOS (or JS); commonTest covers core semantics; no JVM types in common API; docs list the support matrix.
- **Status:** ⏸ Deferred to v2.0 — 1.x publishes JVM 17 artifacts that Android and server code consume; the source-set split, `expect/actual` primitives and non-JVM targets are not implemented. The current support matrix and the port/adapter design that will carry into v2.0 are documented in `docs/37-kmp.md`.

#### G-28 — Android module
- **Problem:** essential Android behavior is app-specific (I2).
- **Work:** `ilogger-android` with Logcat sink (level/tag mapping, no `println`), `ProcessLifecycleOwner` flush hooks, `StrictMode`-safe writes, crash handoff buffer, optional WorkManager shipping, Android actor/session provider, and a one-line initializer.
- **Acceptance:** the reference app uses the module instead of app-local code; instrumented tests cover lifecycle flush and Logcat output; no ANR from logging.
- **Status:** ✅ Implemented (2026-10-22) — new `ilogger-android` module (minSdk 28, publishes via Vanniktech, constrained in `ilogger-bom`): `LogcatSink` (Log prioritization, per-event tags, 3,800-char splitting, `DirectLogSink`), `AndroidFileSink` (delegating `TextFileSink` without `DirectLogSink`, so all writes including batches hop to `ilogger-android-file`; StrictMode/ANR-safe), `AndroidLifecycleHooks` (ProcessLifecycleOwner `ON_STOP` → `flush()` on IO), `CrashBuffer` + `CrashHandoff` (bounded ring, `fd.sync`, synthetic `android.crash`, drain/pending API, previous-handler delegation), `AndroidActorProvider` (persisted session automation actor + in-memory user with `session` attribute), `ILoggerAndroid.install` one-call setup, and optional WorkManager shipping (`ShippingTransport` SPI, durable `ShippingSpool`, `ShippingSpoolSink`, `LogShippingWorker` draining ten bounded batches with backoff; WorkManager compile-only). The reference app now routes Logcat through `LogcatSink`, JSONL audit through `AndroidFileSink`, binds the crash ring with installed handoff and drains reports on next launch, and flushes on process stop through `AndroidLifecycleHooks`; the pre-existing AGP 9 `BuildConfig` failure was fixed with `buildConfig = true`. JVM tests cover Logcat mapping/chunking/tags, actor persistence and user precedence, crash ring/report writing/delegation, JSONL file output, spool append/ack and runner success/failure/byte budgets; instrumented tests compile and cover process-stop flush, SharedPreferences session persistence, filesDir JSONL and the default Logcat writer (device CI job lands with G-42; not run locally — no emulator). Docs: new `docs/28-android-module.md`, sinks table/section, Android app guide and README updated.

#### G-29 — Remote/HTTP sink
- **Problem:** centralized collection requires a bespoke sink (I8).
- **Work:** `ilogger-remote` with batching, gzip, auth header providers, retry/backoff, idempotency key = event id, offline spool (file or SQLite), circuit breaker, TLS/pinning hooks and a dead-letter fallback; engine-agnostic transport SPI (Ktor/OkHttp/Java HTTP adapters).
- **Acceptance:** MockWebServer tests cover success, 429/5xx, timeouts, offline spool recovery, duplicate suppression and orderly shutdown; no new core dependency.
- **Status:** ✅ Implemented (2026-10-23) — new `ilogger-remote` (JVM 17+, depends only on `ilogger-core`, serialization and coroutines). `RemoteLogSink` encodes with `EventCodec` (JSONL default), appends to an optional `RemoteSpool`, drains oldest-first, retries timeouts/408/429/5xx with capped exponential backoff and `Retry-After`, removes lines only after 2xx and sends permanent 4xx plus exhausted/no-spool batches to `RemoteDeadLetter`. `Idempotency-Key` is the batch content hash (stable across retries and restarts; per-event ids remain the finer-grained dedupe key), `RemoteTransport` is the engine-agnostic SPI with a dependency-free `JavaHttpTransport` (custom `HttpClient`/`SSLContext` for TLS pinning) and documented OkHttp/Ktor adapters, headers resolve per attempt for token rotation, gzip/batch size/attempts/backoff are configurable, and a circuit breaker skips transport calls during an outage while events spool. `flush()` drains and `close()` stops writes (the engine flushes first). 19 MockWebServer tests cover success, 429/5xx, timeouts, offline spool recovery, content-hash duplicate suppression, permanent-failure dead letters, circuit breaker and shutdown; `docs/33-remote-sink.md` plus sink/README/BOM updates.

#### G-30 — Ktor: correct, testable client + server integration
- **Problem:** server-only, blocking, duplicated events, non-injected clock (I3, I4).
- **Work:** use suspend `record` in hooks; inject `LogClock`; set a default category (`http`); dedupe `CallFailed`/`ResponseSent` by correlation id or mark one as transport failure; add a request sampler/config; add a Ktor client plugin that propagates `traceparent`/correlation and records outbound calls; tests.
- **Acceptance:** one event per request in normal and failure paths (or documented dual-event mode); configurable sampling/exclusions; tests cover client/server and correlation propagation.
- **Status:** ✅ Implemented (2026-10-22) — `HttpAudit` records through suspend `ILogger.record` (the non-suspend `ResponseSent` hook hands the event to the application coroutine scope without blocking the response; `CallFailed` records directly), keeps one event per request by default with a per-call emitted marker and offers `HttpFailureEvents.BOTH` for engine status + handler cause, and gained `requestFilter` plus once-per-call rate `sampling` (recording only; correlation ids are still established). New `HttpAuditClient` plugin records one `http.client.request` per send (method/host/path/status/duration, category `http`, source `ktor.client`), uses the injected `LogClock`, reads ambient actor/correlation, resolves `X-Request-Id` as existing header → ambient correlation, forwards `LogContext.attributes["traceparent"]` or optionally generates a W3C-valid one, marks transport exceptions with `transport = true` and rethrows them, and samples/filters without suppressing header propagation. `HttpAuditClientTest` (10 tests) covers success/404/500, transport errors, actor/correlation/explicit-header precedence, traceparent forwarding and generation, sampling, filtering and client→server correlation; `HttpAuditTest` (9 tests) adds filtering, sampling and dual failure mode. Docs: `docs/20-ktor-integration.md`, README module row, correlation guide, app recipe.

#### G-31 — Room: query, observation, retention and safety
- **Problem:** minimal DAO and retention behavior (I5, I6).
- **Work:** add Flow queries and filters (time range, level, action, entity/id, actor, outcome, correlation), paging, count/distinct helpers, export/import (JSONL), scheduled retention catch-up, bind timeout/fail-fast option, schema migration helpers for future versions, optional encryption hook; document multi-process limits.
- **Acceptance:** an audit viewer can be built with the shipped DAO; retention runs on an idle app; unbound target fails clearly at close; tests cover filters/paging/export.
- **Status:** ✅ Implemented (2026-10-22) — `RoomLogFilter`/`RoomLogPage`/`RoomLogOrder` plus DAO helpers `query`, Room-observed `observe`/`observeCount`, `count` and `distinctValues` over level/action/entity/entity id/actor/outcome/source/category/correlation (`@RawQuery` with bound parameters, stable `(ts, eventId)` ordering); `exportJsonLines`/`importJsonLines` round-trip losslessly through paged JSONL with `REPLACE` idempotency and line-numbered errors; `RoomLogRetentionScheduler` sweeps on a timer and catches up after idle/sleep while containing errors; `RoomLogTarget` gained `bindTimeoutMillis` (0 = wait forever) and `RoomLogTargetUnboundException` on timeout and on unbound `close()`; `RoomLogPayloadCipher` encrypts the payload column; `RoomLogSchema.TABLE_VERSION` documents the evolution contract; docs cover bind/close behavior, encryption vs SQLCipher, schema evolution and multi-process limits. The reference app's audit screen now streams `observe(...)` rows. Tests: 39 in `:ilogger-room` green, including filters/paging/counts/facets/observation/export/import/cipher/retention/bind failures against a real in-memory Room database generated by KSP.

#### G-32 — SLF4J, java.util.logging and Logback bridges
- **Problem:** existing JVM logs cannot flow through ILogger (I9).
- **Work:** outbound sink adapters (ILogger → SLF4J/Logback/j.u.l.) and inbound bridges (SLF4J appender → ILogger events), preserving MDC/context, levels and exception payloads; recursion protection; optional dependency modules.
- **Acceptance:** a Spring/legacy app can route third-party logs through ILogger or vice versa; no classpath pollution when unused; tests for recursion/levels/MDC.
- **Status:** ✅ Implemented (2026-10-23) — two opt-in artifacts. `ilogger-jul` (no extra dependencies) ships `JulLogHandler` (JUL `LogRecord` → draft: action `jul.<logger>`, source, message/parameters, original `thrown` as failure) and `JulLogSink` (ILogger → `LogRecord` with timestamp, source, fixed text codec, configurable audit level). `ilogger-slf4j` (SLF4J/Logback `compileOnly`) ships the Logback `ILoggerAppender` (formatted message, `mdc.*` and SLF4J 2 `kv.*` metadata, throwable proxy as failure) and `Slf4jLogSink` (fluent `ilogger.*` key/values for event id/action/entity/actor/correlation). Both directions map all six levels and audit events default to INFO (configurable). Recursion protection combines the reserved `io.ilogger.bridge` logger prefix (cross-thread/async) with a shared thread-local guard (synchronous); inbound MDC reads are hardened against contexts without an adapter. 18 tests (10 JUL, 8 SLF4J/Logback) cover level mapping, MDC/key-values, throwables, filtering and both-bridges-installed recursion; docs in `docs/34-logging-bridges.md`, linked from 14/24 and the README/BOM.

#### G-33 — OpenTelemetry and metrics
- **Problem:** no trace/span correlation or operational metrics (I10).
- **Work:** `ilogger-otel` reads current `traceId`/`spanId` into event attributes, propagates W3C `traceparent` through Ktor/Room context, exports events/spans optionally; Micrometer/OTel metrics for queue depth, dropped events, sink latency, retry counts.
- **Acceptance:** events correlate with spans; metrics available via standard registries; opt-in modules add no core dependency.
- **Status:** ✅ Implemented (2026-10-23) — new opt-in `ilogger-otel` module (OTel API 1.43 `compileOnly`, coroutines `api`, constrained in `ilogger-bom`). A generic admission-time `LogEventEnricher` SPI was added to core (`ILoggerBuilder.enricher`): enrichers run on the producer thread before value adapters, limits, redaction and sampling, explicit metadata wins, earlier enrichers beat later ones, and failures are contained through `onDispatchError`. `OtelTraceEnricher` copies the current OTel span (or a propagated `traceparent`) into `trace.traceId/spanId/sampled/remote/traceparent`; `W3cTraceParent`/`OtelTrace` parse, format and validate W3C ids (lowercase hex, zero-id and `ff` rejection, future-version tolerance) and provide `withRemoteParent`/`withIncomingTraceParent`/`withCurrentTrace` for Ktor, Room and WorkManager hand-offs; `OtelSpanLogSink` exports one instantaneous span per event with `ilogger.*` attributes and ERROR status on failures. `OtelLoggerMetrics` exposes delta accepted/delivered/truncated/dropped counters plus queue, in-flight and per-binding gauges as observable instruments (collection-time reads, no polling thread); `OtelSinkMetrics.wrap` records write duration, event/error counters and detects engine retries, preserving the direct-sink fast path. 45 tests (W3C parsing/formatting, propagation, enricher precedence and containment, span export, metric values, decorator behavior) run against `opentelemetry-sdk-testing`; docs in `docs/35-opentelemetry.md`, cross-linked from 14/26 and the README/BOM.

#### G-34 — Spring Boot/server starter (P2 candidate)
- **Problem:** server setup lacks auto-configuration (I11).
- **Work:** auto-configure logger/sinks from properties, graceful shutdown flush, actuator metrics/health, MDC bridge, profile-aware defaults (dev console, prod JSONL/remote).
- **Acceptance:** a Spring Boot sample logs audit events with zero Java config; shutdown flushes; health reports drops.
- **Status:** ⏸ Deferred (P2) — no starter module ships in 1.0. Server users wire the engine explicitly (see `docs/26-configuration-reference.md`) or through the SLF4J bridge (`docs/34-logging-bridges.md`); graceful flush is covered by the engine's `close()` contract.

### 5.5 P1 — Audit safety and compliance

#### G-35 — Runtime redaction pipeline
- **Status:** ✅ Implemented (2026-10-23) — `RedactionRule` SPI + immutable `RedactionPolicy` on `ILoggerBuilder.redaction`, applied at admission (after rendering/suppression, before limits, sampling and sinks). Built-in rules `RedactionRules.secretKeys/email/phone/card/bearer` plus custom regex/key rules, per-category policies, strict rejection (`DropReason.STRICT_REJECT`) on rule failure. Key rules match the full key path (nested maps under sensitive keys fully masked); `LogSafeValue` and top-level identifiers are exempt. Covered by `RedactionTest` (15 cases: metadata/changes/actor/nested, per-category, strict vs lenient, limits ordering, `LogSafeValue`, concurrency); docs in 09/24/26.
- **Problem:** protection stops at codegen (S1, S2, S3).
- **Work:** `Redactor`/`Sanitizer` SPI applied at event construction (before sampling/sinks); regex and key-based rules (email, phone, card, token, cookie); actor attribute sanitization; per-category policies; strict rejection; tests proving no sink sees raw values.
- **Acceptance:** manual events and actor attributes are redacted; config is thread-safe; zero-cost path when no redactors are configured.

#### G-36 — Tamper-evident audit trail and signatures
- **Problem:** no integrity proof (S4).
- **Work:** optional per-sink/decorator hash chain (`prevHash`, `eventHash`, canonical JSON), HMAC or Ed25519 signing, key rotation, verification API and CLI; document threat model and what each mode protects; keep default off.
- **Acceptance:** modifying/deleting/reordering a record breaks verification; verification works on JSONL/Room export; performance overhead measured.
- **Status:** ✅ Implemented (2026-10-23) — `HashChainSink` decorator stamps every forwarded event with `ilogger.trail.seq/prev/hash/keyId` under one mutex (chain order == write order); `TrailSigner` SPI with `HmacTrailSigner` (HMAC-SHA256, per-thread Mac) and Ed25519 documented as a JDK 15+/Android API 33+ wrap; key rotation via `() -> SigningKey` provider; `TrailVerifier.verify` in keyed mode (recomputes stamps from record content) and structural-only mode, accepting legitimate per-process segment restarts. Canonical chain input is length-prefixed, excludes `ilogger.*` stamps, and mirrors the JSON codec's structure-depth marker so stamps survive a JSONL round trip. Covered by `IntegrityTest` (15 cases: stamps, linking, modification/metadata-change/reordering/in-segment deletion/insertion detection, structural mode limits, rotation, 200-event concurrent chain, batch order) and `JsonEventCodecIntegrityTest` (encode/decode round trips incl. all actor types, JSONL verify + tampered-line detection); the new `JsonEventCodec.decode` makes JSONL verification real (Room exports keep payloads verbatim and verify the same way). Overhead measured: ~6 µs/event on the sink side (benchmark harness case 6; ~1.8 µs canonical + ~3 µs HMAC). Threat model documented in new `docs/30-integrity.md`, referenced from 14/15/25; command-line verification shipped in G-43 (`ilogger verify`, docs/36).

#### G-37 — Encryption at rest
- **Problem:** no built-in encryption (S5).
- **Work:** AES-GCM file sink format with key provider SPI and rotation, SQLCipher integration hook for Room, encrypted payload option; never ship default keys; document key loss consequences.
- **Acceptance:** encrypted files cannot be read without the key; rotation re-encrypts safely; tamper detection tests.
- **Status:** ✅ Implemented (2026-10-23) — `AesGcmCipher` (AES/GCM/NoPadding, fresh 12-byte IV and 128-bit tag per record) emits self-describing `ilogenc1:<keyId>:<base64url(iv‖ciphertext‖tag)>` tokens; `EncryptionKey` validates id and 16/24/32-byte keys, `EncryptionKeyProvider` is the key SPI, and `keysForReading` is the rotation keyring. `TextFileSink` gained an optional last `cipher` parameter (encrypts the codec's full line; rotation/gzip/fsync unchanged) and `AesGcmRoomPayloadCipher` applies the same cipher to the Room `payload` column via `RoomLogTarget(payloadCipher = …)`, with SQLCipher (`openHelperFactory`) documented as the whole-DB option. Acceptance proven by `EncryptionTest` (7 cases: round trips for all key sizes, fresh IVs, bit-flip tamper → `GeneralSecurityException`, wrong/unknown key, malformed tokens, key validation, rotation with keyring vs old-key-only), `TextFileSinkTest` (no plaintext on disk, mid-stream rotation readable by a keyring, tampered file fails), and `AesGcmRoomPayloadCipherTest` (payload-only encryption, tamper detection). Never ship default keys and key-loss consequences documented in new `docs/31-encryption-at-rest.md`, referenced from 14/19/24; CLI decrypt and replay-based key rotation shipped in G-43 (`--decrypt-key`/`--encrypt-key`, docs/36).

#### G-38 — Retention, erasure and legal hold
- **Problem:** compliance workflows require custom code (S6).
- **Work:** per-category/entity retention policies, actor/entity erasure API with deletion audit, legal hold flags, export-before-purge, scheduled retention independent of writes, and policy validation.
- **Acceptance:** GDPR-style erasure removes subject data from shipped stores while preserving audit of the deletion; legal hold blocks purge; tests.
- **Status:** ✅ Implemented (2026-10-23) — Room module: `RoomLogRetentionPolicy`/`RoomLogRetentionRule` for per-category/entity windows (most-specific rule wins; unmatched rows without a default are kept forever; duplicate/invalid rules rejected at construction); `RoomLogRetentionEnforcer` does keyset-paged, hold-aware chunked deletes with a `beforePurge` export hook (a throwing hook aborts the delete) and reports `removed`/`held`; `RoomLogLegalHoldRegistry` holds Event/Actor/Entity/Category/Action flags matched against indexed columns (works with encrypted payloads) and snapshots for persistence; `RoomLogRetentionScheduler` gained an enforcer constructor for idle-time sweeps, while `RoomLogTarget`/`roomLogs` accept a hold registry for write-triggered purges; `RoomLogErasure` implements actor/entity erasure with chunked deletes and one pseudonymous `compliance.erasure` audit event (target kind, 16-hex SHA-256 hash, counts, reason, optional request id; no raw identifier or erased payload), routed through the configured payload cipher. Acceptance covered by `RoomLogRetentionPolicyTest`/`RoomLogLegalHoldTest` (validation, precedence, all hold kinds), `RoomLogRetentionEnforcerTest` (per-category/entity sweep, hold blocks purge then release, archive hook ordering, throwing hook aborts, unmatched rows forever, 600-row pagination), `RoomLogErasureTest` (audit content and pseudonymity, withheld rows, entity scope, no-match audit, encrypted audit, argument validation), and `RoomLogTargetTest` (write-triggered sweep honors holds). Docs in new `docs/32-compliance.md`, referenced from 19/24.

#### G-39 — Event size and resource limits
- **Status:** ✅ Implemented (2026-10-23) — `EventLimits` (`maxEventBytes`, `maxStringLength`, `maxEntries`, `maxActorAttributes`, `maxFailureFrames`) enforced at admission via `ILoggerBuilder.limits`: `LimitAction.REJECT` drops with `STRICT_REJECT` + violation message, `LimitAction.TRUNCATE` bounds in place with the `…[truncated]` marker, `ilogger.truncated` metadata flag and `DeliveryStats.truncated` counter; actions are never truncated. `JsonEventCodec` cuts container recursion with the public `MAX_DEPTH_MARKER` so cyclic/deep metadata cannot overflow the stack. Covered by `EventLimitsTest` (8 cases: reject, truncate, entry/actor bounds, failure frames, cyclic + 10,000-deep structures, byte-budget pressure) and `JsonEventCodecTest` depth-guard cases; docs in 14/15/24/26.
- **Problem:** oversized events can exhaust resources (S7, R13).
- **Work:** configurable max event bytes, metadata entries, string length, failure frames, actor attributes; truncation policy with a marker; `STRICT_REJECT` or truncate-and-count; memory-safe JSON encoding.
- **Acceptance:** fuzz tests never exhaust memory; truncated events are explicit; limits documented per sink.

### 5.6 P1/P2 — Testing, tooling and ecosystem

#### G-40 — TestKit assertion DSL and deterministic async draining
- **Problem:** tests are verbose and async waits are real-time (T2).
- **Work:** add `TestKit` assertion helpers (`assertLogged`, `eventsFor`, `assertNoSensitive`, `awaitEvent(predicate)`), a virtual-time scheduler option, `drain()` using the G-02 barrier, and golden-event builders.
- **Acceptance:** a full generated-wrapper test fits on one screen; async dispatch tests are deterministic and fast; no `Thread.sleep` required.
- **Status:** ✅ Implemented (2026-10-22) — `assertLogged`/`assertNotLogged`/`eventsFor`/`awaitEvent`, `assertNoSensitive` (default credential/PII vocabulary, masked and hashed values accepted, nested maps scanned), `drain()` on the flush barrier, canonical `golden(event)` snapshots, `advanceTimeBy`, and `TestKit.create(workerDispatcher)` for coroutines-test virtual time. `TestKitTest` covers every helper including an `UnconfinedTestDispatcher` async pipeline; `CodegenBehaviourTest` gained a one-screen generated-wrapper snapshot test. Documented in `docs/18-testing.md`.

#### G-41 — Fakes, conformance suite and chaos testing
- **Problem:** no reusable fakes or sink contract tests (T3).
- **Work:** publish test fixtures: in-memory sink, fake file/Room/HTTP sinks, failing/blocking sink, conformance tests every sink should pass (batch, flush, close, concurrency, idempotency), and property tests for codecs/parsers.
- **Acceptance:** new sinks can validate against the shared suite; codec/parser property tests run in CI.
- **Status:** ✅ Implemented (2026-10-22) — `ilogger-testing` publishes `InMemoryLogSink` (with `TestLogSink` as its TestKit-facing subtype), `FakeLogSink` (records events, batch envelopes and codec payloads; transient/cadence write failures, flush/close faults and virtual-time delays), `BlockingLogSink` (one-shot gate that close releases), seeded deterministic `ChaoticLogSink`, and `RecordingDeadLetterSink`. `LogSinkConformance` is an abstract JUnit 4 suite covering single/batch writes, ordering, empty batches, flush/close idempotency, retention across close, equal timestamps, the `DirectLogSink` fast path and loss-free concurrent writers; it is adopted by `InMemoryLogSink`, `FakeLogSink`, `TextFileSink` and `RoomLogTarget`, each running all 8 contract tests. Seeded property tests (`JsonEventCodecPropertyTest`, `RoomLogImportPropertyTest`) generate quotes, backslashes, newlines, control characters, Unicode, nested/unknown values and assert single-line parseable output, exact string round-trips, determinism, lossless Room export/import and line-numbered parser failures; they run under `:ilogger-serialization:build`/`:ilogger-room:build` in CI. Docs: `docs/18-testing.md` gained fixture/conformance/chaos/property sections; README module row updated.

#### G-42 — CI, API compatibility, coverage and docs automation
- **Problem:** limited platform/quality gates (T4).
- **Work:** Windows/macOS/Linux JVM matrix; Android instrumented tests; Kover coverage with thresholds; binary-compatibility-validator API dumps; Dokka publishing; benchmark regression gate; composition of all modules; dependency/security updates; reproducible builds.
- **Acceptance:** PRs fail on API/coverage/benchmark regressions; docs published per release; Windows rotation tests run.
- **Status:** ✅ Implemented (2026-10-23) — root-level quality gates: binary-compatibility-validator 0.18.2 with committed dumps for `ilogger-annotations/core/ksp/testing/room/serialization/ktor` (`apiCheck` wired into `check`; Android excluded because AGP 9's built-in Kotlin has no BCV hook); Kover 0.9.11 line-coverage floors enforced by `koverVerify` in `check` — core 78%, testing 88%, room 82%, serialization 95%, Ktor 95% (baselines 80.5/90.9/85.5/98.0/98.8); `ilogger-ksp` covered end-to-end by generated-code fixtures instead because Kover cannot instrument Gradle workers; Dokka 2.2.0 applied to every published artifact (including Android), producing real Javadoc jars for Maven Central and a per-release `api-docs.zip`; reproducible archives (`isPreserveFileTimestamps=false`, `isReproducibleFileOrder=true`) with a CI two-build hash check; CI gained a Windows/macOS matrix running the module and `TextFileSink` rotation suites, an API 34 emulator job running `:ilogger-android` and `:app` instrumented tests, explicit `apiCheck`/`koverVerify` steps, and a Dokka build; Dependabot covers Gradle, the included plugin build and Actions with Kotlin/KSP grouped and KotlinPoet pinned. Docs: `docs/29-delivery-and-quality-gates.md`, README index, changelog.

#### G-43 — CLI and audit tooling
- **Problem:** no way to inspect/verify/export shipped data (I12).
- **Work:** `ilogger-cli` commands: `tail` (JSONL), `verify` (hash chain), `export` (CSV/JSON from Room/JSONL), `schema` (registry), `replay` (inject into a sink); optional GUI/Compose viewer module later.
- **Acceptance:** CLI can verify a tamper-evident file and export Room events; commands documented with examples.
- **Status:** ✅ Implemented (2026-10-23) — new `ilogger-cli` JVM application (`application` plugin, `installDist`, intentionally not published): `tail` (compact/`--json`, level/action/entity/actor/category/source/time filters, `--limit`, `--follow` + poll interval), `verify` (keyed HMAC and structural `TrailVerifier` modes, per-record physical line mapping, `--json` verdict), `export` (JSONL or direct Room SQLite reads through `androidx.sqlite:BundledSQLiteDriver` with no Room dependency; CSV/JSON/JSONL, `--from`, `--table`, `--output`, filters), `schema` (KSP manifest validation — formatVersion, duplicate schemaIds — table/JSON output and action prefix filters), and `replay` (console/`jsonl:` targets, filters, append/`--overwrite`, ids and timestamps preserved by bypassing the engine). AES-GCM read support via `--decrypt-key` keyrings on every read command and `--encrypt-key` on replay close G-37's rotation tooling; `schema` closes G-36's verification tooling. Key specs support `base64:`/`base64url:`/`hex:`/`file:` with duplicate-id rejection and never echo material; exit codes 0/1/2. 83 tests (Options, filters, keys, each command, Room/encrypted paths, follow mode, CLI dispatch) at ~96.8% line coverage (80% floor in `coverageFloors`); CI builds the distribution in the main JVM job and runs CLI tests on Windows/macOS; docs: `docs/36-cli.md` plus 29/30/31/README/changelog updates.

#### G-44 — Documentation and adoption assets
- **Problem:** docs are strong but will lag the expanded surface.
- **Work:** update all 27 topics; add KMP, Android, remote, redaction, integrity, migration and performance-tuning guides; add compatibility matrix; every feature has a runnable example; add a 5-minute quickstart and one-page cheatsheet; keep the reference app current.
- **Acceptance:** docs build in CI; every code sample compiles; no stale limitations; examples run in CI.
- **Status:** ✅ Implemented (2026-10-23) — the topic set grew to 40 and the README index, reading order (`docs/01-overview.md`) and changelog were updated to match. New: `docs/37-kmp.md` (current JVM 17/Android 28+ reality, support matrix, port/adapter layout for KMP, v2.0 plan), `docs/38-compatibility.md` (tested toolchain versions, artifact/platform/dependency-scope matrix, SemVer + KSP versioning policy, known limits), `docs/39-migration.md` (incremental adoption, SLF4J/JUL and hand-rolled-audit mapping, six-step rollout, common mistakes, rollback) and `docs/40-cheatsheet.md` (setup → annotations → knobs → sinks → compliance stack → TestKit → CLI on one page). A five-minute end-to-end path now opens `docs/02-quickstart.md`; the KMP section in `docs/03-ksp-setup.md` points at 37 and the roadmap candidates in `docs/24-limits-and-constraints.md` were refreshed. Android, remote, redaction, integrity and performance guides already existed (21/28, 33, 09/35, 30/31, 25). The runnable-example acceptance is covered by the `examples/*` and `:app` CI jobs; markdown topics are indexed rather than built, while Dokka API docs build in the Android and release jobs. G-27 (full KMP) and G-34 (Spring Boot starter) remain deferred to v2.0/P2 by design.

---

## 6. Proposed release sequence

The sequence is a dependency order, not a date commitment.

| Release | Theme | Goals | Notes |
|---|---|---|---|
| **v1.0.x** | Hotfixes and truthfulness | G-05, G-06 rotation fix, G-07 cancellation, G-08 KSP rounds/incremental, G-12 tests, docs corrections | No new public API required; ship quickly. |
| **v1.1** | Trustworthy core | G-01, G-02, G-03, G-04, G-09, G-10, G-11, G-13, G-14, G-15, G-40 | The “production-grade engine” release; additive APIs, deprecations only. |
| **v1.2** | Developer love | G-17, G-18, G-19, G-20, G-21, G-22, G-23, G-24, G-25, G-26, G-27 (JVM/Android first), G-28, G-30, G-31, G-41, G-42, G-44 | KSP reach + Android/Ktor/Room polish + setup ergonomics. |
| **v1.3** | Operations and compliance | G-16, G-29, G-32, G-33, G-35, G-36, G-37, G-38, G-39, G-43 | Remote shipping, redaction, integrity, retention, CLI. |
| **v2.0** | Everywhere and provable | G-27 (full KMP), G-09 finalization, G-17 compiler plugin, any breaking event-model changes | Reserve a small, well-documented breaking window; provide migration tooling. |

Dependencies worth respecting:

- G-02 is a prerequisite for deterministic tests (G-40) and reliable remote shipping (G-29).
- G-01 should land before G-16/G-29 so per-sink rate limiting and spooling have an isolation point.
- G-09 (event construction API) should precede G-10/G-35/G-36 to avoid duplicating validation/redaction in old signatures.
- G-27 (KMP) forces `expect/actual` decisions that affect G-06/G-28/G-36; start the design spike early even if the release is v2.
- G-22 (schema manifest) is a prerequisite for true compatibility checks in G-42.

---

## 7. Success metrics

### Reliability
- 0 silent event losses on graceful `close()`; every drop has a `DropReason` and counter.
- 100% of wrapped operations preserve the original exception identity, including cancellation.
- Slow/failing sinks never delay unrelated sinks beyond a documented bound.
- Crash/restart tests prove the configured durability claim per sink (buffered / OS / fsync / remote spool).

### Speed
- Disabled logger: ≤ 2 ns/op.
- Filtered diagnostic: zero allocations, no message formatting.
- Async enqueue: ≤ 1 µs/op on the producer thread.
- Buffered audit (excluding sink IO): ≤ 3 µs/op.
- File/room/remote sinks process engine batches, never per-event calls under `BUFFERED`.
- No benchmark regression > 15% without an explicit waiver.

### Simplicity / developer experience
- New project: one plugin + one dependency block + one wiring line.
- Valid `@Log` code compiles without warnings; every rejection includes a copy-paste `Fix:`.
- Incremental no-op build generates no files and adds < 2 s KSP overhead.
- A developer can go from zero to a stored audit event in ≤ 5 minutes using the quickstart.
- Every integration has a runnable example and a tested snippet in the docs.

### Audit / safety
- Strict mode catches every non-loggable metadata value and oversized event in tests.
- Redaction tests prove raw secrets never reach a sink.
- Tamper verification detects insert/delete/reorder/modify on hash-chained stores.
- Schema registry detects duplicate actions and incompatible version changes in CI.

### Coverage
- Core ≥ 90% line coverage; KSP/codegen ≥ 85%; every integration module has tests for success and failure paths.
- JVM + Android + at least one non-JVM KMP target in CI (after G-27).

---

## 8. Non-goals and constraints to preserve

- **No runtime reflection or bytecode magic in generated wrappers.** Compile-time proof remains the product; the optional compiler plugin is compile-time only.
- **No unbounded buffers or implicit memory growth.** Every queue has a capacity and a stated policy.
- **No audit sampling by default.** Audit events are only dropped by explicit configuration/policy and are always counted.
- **No wholesale object serialization.** Metadata remains explicitly selected; convenience adapters must be opt-in and documented.
- **No database diffing.** `LogChange` stays caller-provided.
- **No sink-side unmasking/decryption of masked values.** Protection must be applied before storage.
- **No automatic action names from method names.** Actions remain canonical identifiers.
- **No network behavior in core.** Remote shipping is an optional module with explicit configuration.
- **No framework dependencies in `ilogger-core`.** Android/Room/Ktor/OTel remain optional.
- **No silent action/version changes.** Schema evolution is explicit and validated.

---

## 9. Definition of done for every goal

1. Public API and KDoc (with `@since`/version notes where relevant).
2. Unit + integration tests covering success, failure, cancellation, concurrency and limits.
3. Negative/diagnostic tests for compile-time rules.
4. Documentation update (topic doc + configuration reference + quickstart/recipe if user-facing).
5. Changelog entry under `Unreleased`.
6. Runnable example or demo-app screen for user-facing features.
7. Benchmark result for hot paths and a note on allocation behavior.
8. API compatibility check (binary-compatibility-validator) and no new mandatory transitive dependency in core.
9. Migration note for any deprecation or behavior change.

---

## 10. Open decisions

1. **KMP timeline and artifact layout.** Full KMP in v1.x vs v2.0; whether to publish platform-specific artifacts alongside the existing JVM coordinates. Recommendation: design spike in v1.1, implementation in v2.0; keep JVM coordinates stable.
2. **Cancellation representation.** Add `Outcome.CANCELLED` (source-breaking for exhaustive `when`) or keep `Outcome.FAILURE` plus `LogFailure.cancelled`. Recommendation: `LogFailure.cancelled` + skip-by-default in v1; revisit enum in v2.
3. **Synchronous API.** Should `recordNow(dispatch = INLINE)` become legal via a bounded blocking bridge, or should `recordBlocking` be a separate API? Recommendation: separate API to keep `recordNow` non-blocking by contract.
4. **KSP reach.** Subclass wrappers for open/abstract classes are straightforward; final classes need a compiler plugin. Decision needed on whether maintaining a compiler plugin is in scope before v2.
5. **Remote transport engine.** Keep the remote module engine-agnostic with adapters (Ktor/OkHttp/Java HTTP), or pick one default. Recommendation: SPI + one tested adapter.
6. **Tamper evidence default.** Off by default (performance/complexity) or on for `AUDIT` sinks? Recommendation: off; enable per binding/profile.
7. **Schema registry format.** Kotlin DSL + generated JSON manifest vs annotation-only export. Recommendation: generated JSON + constants generated from a DSL, to serve both compile-time and external tooling.
8. **Strict mode default.** WARN in debug and OFF in release is safe; decide whether `THROW` is available in release for high-compliance apps.
9. **Extractor capability.** Whether suspend extractors are worth the codegen/runtime complexity; recommendation: no in v1, revisit with DI registry.
10. **Remote/PII processor.** Whether to use a separate `ilogger-processor` module for consumer flows (e.g., domain-packaged `@Log` processing on generated code). Not required for v1.x.

---

## 11. Backlog (unprioritized ideas)

- Typed category constants and a `Category` value class.
- `EventId`/`EntityId` value classes to prevent string mixing.
- ULID/KSUID id generator with time-ordered ids.
- Monotonic sequence + ingest timestamp (covered by G-10/G-03).
- Multi-tenant and data-residency routing.
- Priority lanes for audit vs diagnostics (part of G-01/G-16).
- `RetryingSink`, `BatchingSink`, `DeduplicatingSink`, `FilteringSink` decorators.
- Sink circuit breaker and health endpoint.
- Multi-process file locking and `.lock` metadata.
- Log-once-per-key helper and windowed dedupe.
- Snapshot/golden testing for event schemas.
- KSP quick-fix integrations (IntelliJ plugin).
- Gradle build scan/report for instrumentation coverage.
- Compose UI event tail module with filters and pause/resume.
- Web viewer for JSONL with integrity verification.
- Exporters: CSV, Parquet, OpenTelemetry logs, Sentry breadcrumbs.
- Spring Boot actuator `/ilogger` health, Micrometer meters.
- Ktor client/server traceparent propagation (part of G-30).
- Hilt/Koin modules for core logger and sinks (beyond DAO bindings).
- A one-line `ilogger-android` `install(context)` that configures Logcat, Room and lifecycle flush.
- Benchmarking against java.util.logging/SLF4J for fairness.
- Fuzz tests for action grammar, JSON codec and KSP parser.
- Documentation translations and a sample “audit compliance checklist”.
