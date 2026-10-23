# ILogger Full Roadmap (G-06 … G-44) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement every remaining goal in `GOALS.md` (G-06 … G-44) in dependency order, each with tests, docs, changelog entry and an example where the goal changes user-facing behavior.

**Architecture:** The roadmap is split into five release trains. Each train is executed as its own detailed plan (this document owns the ordering, the cross-goal API decisions and the acceptance map; the per-train plans own step-level TDD). G-01 … G-05 are already implemented. Core remains framework-free: every integration lives in its own published module.

**Tech Stack:** Kotlin 2.2.10 / JVM 17 (`-Xexplicit-api=strict`), kotlinx-coroutines 1.11.0, KSP 2.3.12, KotlinPoet 2.2.0, Room 2.8.5, Ktor 3.x, JUnit 4, JMH (kotlinx-benchmark), Kover, binary-compatibility-validator (new), Gradle TestKit (new), Android Gradle Plugin (app module).

**Spec:** `GOALS.md` (sections 3 and 5). Each goal's Problem/Work/Acceptance is the requirement; this plan adds the decisions the implementer cannot derive from it.

## Global Constraints

- `ilogger-core` has no Android, Room, Ktor, serialization, DI or logging-framework dependency. New framework support ships as a separate `ilogger-*` module.
- Public API is explicit (`-Xexplicit-api=strict`) and documented with KDoc; every new public symbol gets a KDoc paragraph stating its guarantee.
- Every loss path reports exactly one `DropReason`; audit events are never sampled or dropped silently.
- New defaults must be safe: `strictMode = OFF` (zero cost), integrity/encryption/redaction off unless configured, `onCancellation = SKIP`.
- Every goal updates `CHANGELOG.md`, the relevant `docs/*.md`, at least one test (JUnit 4 unless a module already uses another framework), and an example or reference-app screen when it changes user-facing behavior.
- No `Thread.sleep` in new tests; use `awaitUntil` / `TestKit` / virtual time.
- Do not break existing source compatibility before v2.0; additive defaults only. Deprecations carry `ReplaceWith`.
- All Gradle work must pass `./gradlew test` on Windows and keep the configuration cache enabled.

## Review Focus

Five input/failure classes the roadmap implies but GOALS.md does not spell out; each gets a test in the task that owns the code:

1. **Rotation on a pre-existing target file / `maxFiles = 0/1`** — a file sink restarted with archives already present must not throw or delete the active log.
2. **Cancellation that arrives while a FAILURE event is being recorded** — logging must never replace the original cancellation identity, even under `ErrorMode.PROPAGATE`.
3. **A processor-generated `@Log` interface in round 2** — wrappers must be generated without duplicate-file errors or regeneration of round-1 wrappers.
4. **A `null`/mutable caller map passed to manual `record`** — later mutation must not change the event a sink already received (G-09).
5. **`close()` racing an in-flight producer** — after `closeAndReport`, no accepted event may be silently dropped without a `DropReason` (G-02 invariant, re-tested under new sinks).

---

## Release trains

| Train | Goals | Detailed plan | Status |
|---|---|---|---|
| v1.0.x — truthfulness | G-06, G-07, G-08, G-12 | `2026-10-21-v1.0.x-hardening.md` | in progress |
| v1.1 — trustworthy core | G-09, G-10, G-11, G-13, G-14, G-15, G-40 | `2026-10-21-v1.1-core.md` (written when the train starts) | pending |
| v1.2 — developer love | G-17 … G-28, G-30, G-31, G-41, G-42, G-44 | `2026-10-22-v1.2-developer.md` | pending |
| v1.3 — operations & compliance | G-16, G-29, G-32 … G-39, G-43 | `2026-10-23-v1.3-compliance.md` | pending |
| v2.0 — everywhere & provable | G-27 full KMP, G-09 finalization, G-17 compiler plugin | `2026-10-24-v2.0-kmp.md` | pending |

The v1.1+ plans are written at the start of each train so their step-level details reflect the code produced by earlier trains. This master plan fixes their scope and API decisions now, so parallel work cannot pick conflicting names.

---

## Train 1 — v1.0.x (detailed in the sibling plan)

### G-06 — Harden `TextFileSink`

**Files:** modify `ilogger-core/src/main/kotlin/io/ilogger/core/sink/TextFileSink.kt`; create `ilogger-core/src/test/kotlin/io/ilogger/core/TextFileSinkTest.kt`; update `docs/14-sinks.md`, `docs/24-limits-and-constraints.md`, `docs/26-configuration-reference.md`.

**API decisions:**
- Keep the existing constructor parameters (source compatibility) and append:
  `fsyncEveryWrite: Boolean = false`, `ioDispatcher: CoroutineDispatcher? = null`, `clock: LogClock = SystemLogClock`.
- Extend `Rotation` with defaulted trailing parameters: `maxAgeMillis: Long? = null`, `gzip: Boolean = false`, `maxTotalBytes: Long? = null`.
- Durability names in docs: **BUFFERED** (`flushEveryWrite = false`), **OS** (`flushEveryWrite = true`), **FSYNC** (`fsyncEveryWrite = true`).
- Archives: active file is `<name>`; size/gzip archives are `<name>.1` … `<name>.<maxFiles>` (gzip: `<name>.<n>.gz`). `maxFiles` counts archived files; `0` keeps none.
- Time rotation is evaluated on write (no background timer) using `clock.nowMillis()` and the archive path; document that an idle sink rotates on its next event.

**Acceptance tests:** `maxFiles = 1, 2, 5`, more than `2 × maxFiles` rolls, restart with existing archives, `maxTotalBytes` pruning, gzip round-trip, time rotation with `FixedClock`, content survives `close()`, `ioDispatcher` delivery, no exception at any roll count.

### G-07 — Cancellation-aware instrumentation

**Files:** `ilogger-annotations/.../Log.kt` (new param), new `ilogger-annotations/.../OnCancellation.kt`, `ilogger-core/.../EventDetails.kt`, `ilogger-ksp/.../OpParser.kt`, `ilogger-ksp/.../Model.kt`, `ilogger-ksp/.../Codegen.kt`, `ksp-tests/.../Library.kt`, `ksp-tests/.../CodegenBehaviourTest.kt`, `docs/04-annotations.md`, `docs/23-diagnostics-catalog.md`.

**API decisions:**
- `public enum class OnCancellation { SKIP, LOG }` in `io.ilogger.annotations`.
- `@Log(onCancellation: OnCancellation = OnCancellation.SKIP)`.
- `LogFailure` gains `public val cancelled: Boolean = false` and `public val isCancellation: Boolean get() = cancelled`; `LogFailure.from` sets it from `kotlin.coroutines.cancellation.CancellationException`.
- Generated wrappers always catch `kotlin.coroutines.cancellation.CancellationException` before `Throwable`, use `logger.recordNow(...)` (non-suspending) for the opt-in cancellation event, wrap that call in its own `try/catch`, and rethrow the original exception by identity. `SKIP` rethrows without touching the logger.
- The cancellation event keeps `Outcome.FAILURE` but carries `LogFailure.cancelled = true`; no `Outcome.CANCELLED` until v2.0.

**Acceptance tests:** default cancellation emits nothing and preserves identity/message; `LOG` emits one FAILURE marked cancelled; a throwing logger in the cancellation path never replaces the original exception.

### G-08 — Multi-round and incremental KSP

**Files:** `ilogger-ksp/.../ILoggerProcessorProvider.kt`, `ilogger-ksp/.../OpParser.kt` (defer hook only), `ksp-tests/src/main/kotlin/ksp/test/library/Library.kt`, new second processor `ksp-tests/src/main/kotlin/ksp/test/generated/GeneratedLibraryProcessor.kt` + provider registration, `ksp-tests/src/test/.../CodegenBehaviourTest.kt`, `docs/03-ksp-setup.md`.

**API decisions:**
- Remove the `invoked` guard. State per processor instance:
  `processedInterfaces: MutableSet<String>`, `processedDatabases: MutableSet<String>`, `diagnosticsWritten: Boolean`.
- Defer a `KSAnnotated` when any parameter/return type `isError`; return deferred symbols from `process`.
- Wrapper dependencies: `Dependencies(aggregating = false, interfaceDecl.containingFile!!)`.
- Facade/Hilt/Koin dependencies: aggregating over all files (they reference multiple DAOs).
- A database with unresolvable accessors is deferred, not partially generated. Generated databases are never regenerated.
- Diagnostics report is written once (fixture mode), after round 1 diagnostics.

**Acceptance tests:** second processor generates a `@Log` interface that is wrapped; two rounds do not duplicate files; a source interface in round 1 is still wrapped exactly once; deferred symbols resolve in the next round. Incremental no-op is verified by asserting isolating `Dependencies` through a Gradle TestKit fixture if TestKit is available offline; otherwise the test asserts round count through a recording `CodeGenerator` fake and the limitation is documented in `docs/03-ksp-setup.md`.

### G-12 — Critical test gaps

**Files:** new tests under `ilogger-core/src/test/...` (`ConsoleLogSinkTest`, `TextFileSinkTest`, `LogContextsTest`, `ChaosSinkTest`), `ilogger-ktor/src/test/...` (`HttpAuditTest`), `ilogger-testing/src/test/...` (`TestKitTest`), `ilogger-serialization/src/test/...` (codec round-trip/property-style cases).

**API decisions:** no production API changes unless a test exposes a bug; bug fixes land with the test. Ktor tests use `testApplication` from `ktor-server-test-host` (add `testImplementation` only to `ilogger-ktor`).

**Acceptance:** console/text/composite/context/Ktor/TestKit have golden + failure-path tests; chaos fixtures (throwing, blocking, close-failing, re-entrant) are part of the shared fixtures; `./gradlew test` is green on Windows.

---

## Train 2 — v1.1 trustworthy core

### G-09 — Defensive event construction and stable recording API

- New `LogEventDraft` (core) with the same fields as `LogEvent`, all defaulted; `ILogger.record(draft: LogEventDraft)` and `recordNow(draft)`, `recordBlocking(draft, timeoutMillis)`.
- `LogEvent` copies `metadata`/`changes` into unmodifiable containers in `init`.
- Keep the 14-parameter overloads, delegate to drafts; mark `@Deprecated` only in v2.0.
- Add `LogEvent.filtered`/`copyWith` only if a later goal needs it (YAGNI).
- Tests: mutation after `record` cannot alter sink output; a fake `ILogger` implements the draft overload only.

### G-10 — Correct sampling + visibility

- New `SamplingContext(level, action, category, outcome, metadata)`, `Sampler.shouldSample(context)` default bridging to the old `(level, rate)` SPI (keep old `SamplingStrategy` working).
- `ActionSampler`, `CategorySampler`, `RateLimitSampler` (deterministic `Random` injected, default `kotlin.random.Random.Default`).
- Per-operation decision keyed by correlation id when START/SUCCESS/FAILURE share it; a sampler decision is cached for the operation and reused.
- `ILogger.isLoggable(level, action, category): Boolean` and `enabled` short-circuit.
- Audit remains unsampled unless an explicit `auditSampling` config is set.

### G-11 — Strict runtime validation

- `StrictMode { OFF, WARN, THROW }` in core, builder `strictMode` (default OFF; reference app uses WARN in debug).
- Validation at event admission: metadata keys/values, actor attributes, string lengths, max event bytes, max entries.
- `LogSafeValue` marker wrappers (`LogSafeValue.of(...)`) allow explicit opt-in for otherwise rejected objects.
- Rejections report `DropReason.STRICT_REJECT` through `onDrop` and increment stats; `THROW` additionally throws `IllegalArgumentException` from `record*` (never from generated wrappers' success path — wrappers still use the configured behavior).

### G-13 — Performance budgets and regression gate

- Extend `:benchmark` JMH: disabled, filtered diagnostic, async enqueue, buffered audit, wrapper dispatch, metadata extraction, file sink, per-sink pipelines.
- Budgets as constants in `benchmark/src/main/kotlin/.../Budgets.kt`: disabled ≤ 2 ns/op, async enqueue ≤ 1 µs/op, buffered audit ≤ 3 µs/op.
- A deterministic (non-JMH) gate test fails when median exceeds budget by > 15%; CI job runs it on Linux.

### G-14 — Backpressure-aware producer helpers

- `ILogger.tryRecord(...): Boolean` (never blocks, never throws; `false` on any drop), `recordOrDrop(...)`, `isBacklogged(): Boolean`, `availableCapacity(): Int`, `hasSinks(): Boolean`.
- Per-binding depth exposed through `BindingStats` in `DeliveryStats`.
- Tests across DROP_OLDEST/DROP_NEWEST/BLOCK/FAIL.

### G-15 — Async/non-blocking file and sink IO

- `TextFileSink` `ioDispatcher` (already added in G-06) becomes the documented default `Dispatchers.IO.limitedParallelism(1)` for async use; `writeDirect` documents its blocking contract.
- Add `AsyncFileSink`? No — G-06's `ioDispatcher` + engine per-binding worker already removes blocking from producers; G-15 adds a throughput benchmark and a test that `Dispatchers.Default` is never used for file IO.

### G-40 — TestKit assertion DSL and deterministic draining

- `TestKit.awaitEvent(predicate)`, `eventsFor(action)`, `assertLogged(action)`, `assertNoSensitive(key)`, `drain()` delegating to `awaitIdle`, `golden(event)` builder.
- `TestKit.create(dispatch = ASYNC)` with a test dispatcher and `advanceUntilIdle` support; `drain()` never sleeps.
- Tests: one-screen generated-wrapper test, deterministic async test without `Thread.sleep`.

---

## Train 3 — v1.2 developer experience

### G-17 — Classes, abstract/open classes, top-level functions

- KSP: accept `ClassKind.CLASS` when `open`/`abstract`; generate an open delegating subclass only for `open`/`abstract` members annotated `@Log`; top-level functions get a `<Name>_ILogger.kt` adapter object (`inline fun` wrappers) plus a `.logged(logger)` extension where a receiver exists.
- Final classes/constructors: diagnostic with a `Fix:` pointing at the planned compiler plugin (G-17b); plugin spike lives in `compiler-plugin-spike.md` (design only until v2.0).
- Tests compile examples for interface/abstract/open/top-level; diagnostics for final class state the limitation.

### G-18 — Generics, nullable results, streams

- Wrapper type parameters copied to the generated class/function; `Result<T>` and nullable returns unwrapped for entity/id inference only (event payload still sees the original value).
- `loggedFlow(logger, action, ...)` / `loggedChannel(...)` adapters in a new `ilogger-flow` module (depends on coroutines only) emitting START on subscription, SUCCESS on completion, FAILURE on error, and a cancellation-marked FAILURE with `onCancellation = LOG`.
- `@Log` on Flow stays rejected.

### G-19 — Reserved names

- Resolve source expressions by parameter index, not name; generated locals use an `ilogger` prefix (`iloggerResult`, `iloggerCtx`) so `context`, `actor`, `result` parameters are accepted.
- Tests: `context: Context`, `actor: String`, `result: Int`.

### G-20 — Expanded safe value model

- KSP `Ks.isLoggableValueType`: add `Char`, `UByte/UShort/UInt/ULong`, `UUID`, `Instant`, `Duration`, `BigDecimal`, arrays, one-level maps, value classes (unwrap `value`), `Result<T>`.
- `ValueAdapter<T>` registry in core (`ILoggerBuilder.valueAdapter(type, adapter)`), used by manual recording; generated code emits `adapter.render(value)` only when annotation names one (new `@LogMeta(adapter = "...")` / `@LogValue(adapter = "...")`).
- KSP option `ilogger.domain-packages` replaces the hardcoded entity package list; default remains the current list.
- Canonical JSON form documented and tested for every type.

### G-21 — Class-level defaults and opt-outs

- `@LogConfig(category, level, dispatch, sensitive, sampling)` on interface/class; method `@Log` fields use sentinel defaults where needed (`sampling` already -1.0; dispatch DEFAULT; level AUDIT needs an `inherit` flag) — add `@Log(useClassDefaults: Boolean = true)` semantics documented so `AUDIT` can still be explicit.
- `@NoLog` on a method excludes it even if inherited from the interface.
- Tests: defaults apply, overrides win, `@NoLog` emits nothing.

### G-22 — Action registry and schema export

- `@LogAction` object generation: annotate a constants object with `@LogActions`; processor emits an `Actions` object plus a manifest `META-INF/ilogger/schema.json` listing action, version, entity, metadata keys, source.
- Diagnostics: duplicate action+version with different schema, non-monotonic version, single-segment warning.
- KSP option `ilogger.schema-output` writes the manifest deterministically; task wiring in the Gradle plugin (G-25).

### G-23 — Real OMIT and parameter sensitivity

- `SensitiveHandling.OMIT`: no key, no extractor call, no allocation; null/absent selection is skipped entirely.
- `@LogSensitive`, `@LogExclude` targets expand to `VALUE_PARAMETER`, `PROPERTY`, `TYPE` (return type), and receiver.
- Tests cover metadata, entity id and changes paths; actor attributes sanitized by policy.

### G-24 — DI-friendly extractors

- `ExtractorRegistry` in core: `register(type, instance)`, looked up once by generated wrapper via `logger.extractor(ExtractorType::class)`.
- Generated wrappers use registry first, then a cached default no-arg instance; `suspend fun extract` supported only when annotation opts in (`suspend = true`) and documented as off the audit hot path.
- Hilt/Koin registrations documented in recipes; no DI dependency in core.

### G-25 — Gradle plugin and BOM

- New `ilogger-gradle-plugin` (Kotlin JVM Gradle plugin, TestKit tests): applies KSP, adds `ilogger-core`/`ilogger-annotations`, adds `ksp("ilogger-ksp")`, wires `ilogger.enabled/strict/schema-output/domain-packages`, variant support for Android, and optional `ilogger { enabled.set(false) }` compile-time disable.
- New `ilogger-bom` publishing platform constraints for every `ilogger-*` module.
- A fresh sample in `examples/gradle-plugin` uses one plugin line.

### G-26 — Better KSP diagnostics and IDE support

- Every diagnostic gets a stable code (`ILG001` …), `Fix:` text, and a generated catalog test that asserts the catalog in `docs/23-diagnostics-catalog.md` matches the processor's codes.
- Warnings: duplicate actions, missing actor on audit, sensitive actor attributes, unused `@LogDao`, no sink binding for a category (when configured).
- Suppression via `@Suppress("ILGxxx")` respected.

### G-27 — Kotlin Multiplatform (full in v2.0; JVM/Android first here)

- Split `ilogger-core` into `commonMain`/`jvmMain` with `expect/actual` for clock, atomic counters, UUID, SHA-256, thread-local context and dispatcher creation; annotations/testing/serialization become KMP.
- Publish JVM + Android (`jvm`/`androidTarget`) first; iOS/JS/Wasm in v2.0.
- `RoomLogEntity` stays JVM/Android; document Room KMP target support.

### G-28 — `ilogger-android`

- New module: `LogcatSink` (level/tag map, no `println`), `AndroidActorProvider` (session id), `ProcessLifecycleOwner` flush hook, StrictMode-safe buffered writer, crash handoff (persist pending events in a file before uncaught-exception handler runs), optional WorkManager shipping.
- Reference app switches its app-local Logcat/lifecycle code to the module; instrumented tests for lifecycle flush.

### G-30 — Ktor client + server corrections

- Server uses suspend `record`, injected `LogClock`, default category `http`, `CallFailed` marked as transport failure with `causedBy`, dedupe flag `logTransportFailures` (default true → one event).
- New Ktor client plugin: reads/propagates `traceparent` and correlation id, records outbound call START/SUCCESS/FAILURE with `onCancellation = SKIP`.
- `ktor-server-test-host` tests for normal, 500, cancellation and client propagation.

### G-31 — Room query/observation/retention/export

- `RoomLogDao`: filtering (time range, level, action, entity, entityId, actor, outcome, correlation), paging (`PagingSource` optional module or `LIMIT/OFFSET` first), counts/distinct, Flow queries.
- Export/import JSONL (uses `JsonEventCodec`), retention catch-up scheduled independently of writes, bind timeout/fail-fast option, optional encryption hook.
- Tests in `examples/room` + JVM test source using Room's bundled SQLite.

### G-41 — Fakes, conformance, chaos

- `ilogger-testing` publishes `InMemoryLogSink`, `FailingSink`, `BlockingSink`, `RecordingDeadLetterSink`, `LogSinkConformance` (JUnit 4 base class: batch, flush, close, concurrency, idempotency).
- Property-style codec/parser tests (JUnit 4 loops over generated cases; no new dependency) for JSON escaping and round-trip.

### G-42 — CI, compatibility, coverage, docs

- GitHub Actions matrix (ubuntu/windows/macos) for JVM tests; Android instrumented job; Kover thresholds (core ≥ 90%, integrations ≥ 80%); binary-compatibility-validator API dumps; Dokka; benchmark gate from G-13; `./gradlew check` composes everything.
- Windows-specific rotation test runs in the matrix.

### G-44 — Documentation and adoption assets

- Update all docs; add KMP, Android, remote, redaction, integrity, migration and performance guides; compatibility matrix; 5-minute quickstart; one-page cheatsheet; every sample compiles and runs in CI (`:examples`).

---

## Train 4 — v1.3 operations and compliance

### G-16 — Rate limiting and adaptive sampling

- `TokenBucketLimiter` in core: per level/action/category/actor keys, deterministic refill clock, `ShedPolicy` (drop diagnostic, never audit); metrics through `DeliveryStats.droppedByReason`.
- `AdaptiveSampler` sheds diagnostics when queue depth > high watermark and restores below low watermark.

### G-29 — `ilogger-remote`

- New module: `RemoteSink` with batching, gzip, auth header provider, retry/backoff reusing engine retry, idempotency key = event id, file/SQLite spool, circuit breaker, TLS/pinning hook, dead-letter fallback.
- `HttpTransport` SPI + Java HTTP adapter; optional Ktor/OkHttp adapters in separate modules to avoid dependencies.
- MockWebServer tests for success/429/5xx/timeout/offline spool/duplicate suppression/shutdown.

### G-32 — SLF4J / JUL / Logback bridges

- `ilogger-slf4j`: `Slf4jLogSink` (ILogger → SLF4J), `ILoggerSlf4jAppender` (SLF4J → ILogger), MDC/context bridge, recursion guard, level map, exception payloads.
- `ilogger-jul`: `JulHandler` inbound and `JulLogSink` outbound.
- Logback appender lives in `ilogger-slf4j` behind an optional compileOnly dependency; tests prove no classpath pollution when unused.

### G-33 — OpenTelemetry and metrics

- `ilogger-otel`: trace/span ids in metadata, W3C `traceparent` propagation through Ktor and Room context, optional span export. Metrics via a `MeterRegistry` SPI (Micrometer/OTel adapters optional).
- No core dependency; zero-cost when absent.

### G-34 — Spring Boot starter

- `ilogger-spring`: auto-configuration from properties, console/JSONL/remote profile presets, graceful shutdown flush, actuator health/metrics, MDC bridge.
- Spring Boot sample logs audit events with zero Java config.

### G-35 — Runtime redaction

- `Redactor` SPI + `RegexRedactor`, `KeyRedactor`; applied at event construction before sampling/sinks; actor attributes sanitized; per-category policies; zero-cost when none configured.
- Tests assert raw values never reach any sink (including dead letters).

### G-36 — Tamper-evident trail

- `HashChainSink`/`SigningSink` decorators: canonical JSON, `prevHash`, `eventHash`, HMAC/Ed25519 via `java.security`, key rotation, verification API; CLI verify command (G-43).
- Default off; threat model doc; performance measured.

### G-37 — Encryption at rest

- `EncryptedFileSink` AES-GCM format (header, nonce, chunking), `KeyProvider` SPI, rotation/re-encryption utility, tamper detection; never ships keys.
- SQLCipher integration hook documented/tested in Room module.

### G-38 — Retention, erasure, legal hold

- Retention policies per category/entity, actor/entity erasure with deletion audit, legal-hold flags, export-before-purge, scheduled retention, policy validation.
- Tests for GDPR-style erasure and legal-hold blocking.

### G-39 — Size and resource limits

- Configurable max event bytes, metadata entries, string length, failure frames, actor attributes; truncation marker; `STRICT_REJECT` or truncate-and-count; memory-safe JSON encoding; fuzz-style tests.

### G-43 — CLI

- New `ilogger-cli` (JVM application): `tail`, `verify` (hash chain), `export` (JSONL/CSV), `schema`, `replay`; later Compose viewer.
- Tests invoke commands against fixture files; docs with copy-ready examples.

---

## Cross-cutting tasks (apply to every train)

- [ ] **API compatibility:** add binary-compatibility-validator in v1.1, dump API after each train, review diffs.
- [ ] **Docs:** each goal updates the relevant topic and `docs/24-limits-and-constraints.md` when a limitation is removed.
- [ ] **Changelog:** one `Unreleased` bullet per goal, user-visible wording.
- [ ] **GOALS.md:** mark `**Status:** ✅ Implemented (date)` with the modules/tests that prove it.
- [ ] **Examples/reference app:** update when behavior becomes user-facing.
- [ ] **Verification:** `./gradlew test` green; Windows-specific tests included; release gate `./gradlew check` green before tagging.

## Self-Review

- **Spec coverage:** every GOALS.md G-06…G-44 appears in exactly one train, with its acceptance criteria restated as tests.
- **Type consistency:** names fixed here (`LogEventDraft`, `StrictMode`, `SamplingContext`, `OnCancellation`, `ExtractorRegistry`, `TokenBucketLimiter`, module names) are the names used by later train plans.
- **Dependencies:** G-02 → G-40/G-29; G-01 → G-16/G-29; G-09 → G-10/G-35/G-36; G-22 → G-42; G-27 spike early. Ordering above respects them.
- **Proportion:** this master plan stays at decision level; step-level TDD lives in the per-train plans.
