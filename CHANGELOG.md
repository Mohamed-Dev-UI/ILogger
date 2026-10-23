# Changelog

All notable changes to ILogger. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[SemVer](https://semver.org/) — the Kotlin DSL surface and artifact layout
remain stable within a major version.

## [Unreleased]

## [2.0.0] — 2026-10-23

### Changed

- **Non-blocking file IO by default**: `TextFileSink` suspend `write`/`flush`
  now always hop to a per-instance
  `Dispatchers.IO.limitedParallelism(1, name = "ilogger-file-io")` view
  (overridable via `ioDispatcher`). File IO never runs on
  `Dispatchers.Default`, producers no longer block on disk under suspend
  dispatch, and two writes to one sink never overlap. `writeDirect` and
  `recordBlocking` keep their explicit synchronous contract. Deterministic
  harness case 5 measures async enqueue + drain at 1,531 ns/event
  (~653k events/s) on the reference host.
- **Faster default event ids**: `UuidEventIdGenerator` now builds RFC 4122 v4
  UUIDs from `ThreadLocalRandom` instead of the shared `SecureRandom` behind
  `UUID.randomUUID`, removing ~1.3 µs per event on hosts where that call is
  slow. Event ids are identifiers, not secrets; inject a
  `SecureRandom`-backed `EventIdGenerator` when unpredictability is required.

### Fixed

- **`ilogger-android` instrumentation actually runs**: the module declared
  `AndroidJUnitRunner` but never depended on `androidx.test:runner`, so the
  test APK aborted at launch with `ClassNotFoundException` and Gradle silently
  reported zero instrumented tests. The runner is now an
  `androidTestImplementation`, and `AndroidLifecycleHooks.attach`/`close` are
  documented as main-thread calls. All four `AndroidIntegrationTest` cases now
  execute and pass on API 36.
- **`ilogger-testing` now dexes on Android**: `LogSinkConformance` used
  backticked sentence names for its `@Test` methods, and D8 rejects spaces in
  class and member names below DEX version 040 (minSdk < 34). Any Android
  module that dexed the artifact failed with "Space characters in SimpleName".
  The eight contract tests now use camelCase JVM names
  (`writeDeliversOneEventAndPreservesContent`, `closeIsIdempotentAndRetainsFlushedEvents`, …);
  assertions, coverage and the subclassing API are unchanged.
- **`TextFileSink` rotation** is now correct for every `maxFiles` value,
  including `0` and `1`, repeated rolls beyond the retention window, and
  archives left behind by a previous process. The oldest archive is deleted
  before shifting, moves replace existing targets, and active writes never
  fail with `FileAlreadyExistsException`.

### Added

- **Documentation and adoption assets (G-44)**: the README now indexes 40
  topics, and four guides close the remaining gaps. `docs/37-kmp.md` states
  the current JVM 17/Android 28+ reality, the source-set layout that works
  today and the port/adapter design planned for v2.0. `docs/38-compatibility.md`
  is the compatibility matrix: tested Kotlin/KSP/AGP/Java versions, artifact
  and platform coverage, dependency scopes, the SemVer and KSP-versioning
  policy, and known platform limits. `docs/39-migration.md` covers incremental
  adoption from ad-hoc logging, SLF4J/JUL and hand-rolled audit tables, with a
  bridge-mapping table, common mistakes and rollback. `docs/40-cheatsheet.md`
  puts setup, annotations, engine knobs, sinks, the compliance stack, TestKit
  and the CLI on one page. A five-minute end-to-end path now opens
  `docs/02-quickstart.md`; the KMP setup section (03), roadmap candidates (24)
  and reading order (01) were refreshed.
- **CLI and audit tooling (G-43)**: new `ilogger-cli` application (not a
  published artifact) with five commands over shipped data. `tail` filters by
  level, action, entity, actor, category, source and time window, prints
  compact or canonical JSONL and follows appends; `verify` recomputes
  `HashChainSink` chains in keyed or structural mode with a per-record line
  verdict and `--json` CI output; `export` reads JSONL or a Room SQLite file
  directly (bundled driver, no Room dependency) and writes CSV, a JSON array
  or JSONL; `schema` validates and filters the KSP action manifest; `replay`
  re-emits events into the console or a JSONL target while preserving ids and
  timestamps. AES-GCM payloads are decrypted with `--decrypt-key` everywhere
  and `replay --encrypt-key` is the supported key-rotation path. Key specs
  accept `base64:`, `base64url:`, `hex:` and `file:` with multi-key keyrings;
  exit codes are 0/1/2 (success, failure, usage). 83 tests, 80% coverage
  floor (baseline ~96.8%), a Windows/macOS CI leg; docs: `docs/36-cli.md`,
  with cross-links from 29/30/31.
- **OpenTelemetry and metrics (G-33)**: new `ilogger-otel` artifact (OTel API
  1.43 `compileOnly`, coroutines `api`). `OtelTraceEnricher` runs at admission
  through a new core `ILoggerBuilder.enricher(...)` hook and copies the active
  span — or a `traceparent` carried in `LogContext.attributes` — into
  `trace.traceId/spanId/sampled/remote/traceparent` metadata.
  `W3cTraceParent`/`OtelTrace` parse, format and validate W3C ids and provide
  `withRemoteParent`/`withIncomingTraceParent`/`withCurrentTrace` for Ktor,
  Room and WorkManager hand-offs; `OtelSpanLogSink` exports one instantaneous
  INTERNAL span per event with `ilogger.*` attributes and ERROR status on
  failures. `OtelLoggerMetrics` publishes delta accepted/delivered/truncated/
  dropped counters plus queue, in-flight and per-binding gauges as observable
  instruments (no polling thread), while `OtelSinkMetrics.wrap` adds per-sink
  write duration, event/error counters and retry detection without losing the
  `DirectLogSink` fast path. Enrichers are a generic additive core hook: they
  run on the producer thread before value adapters, limits, redaction and
  sampling, explicit metadata wins, earlier enrichers beat later ones, and a
  throwing enricher is contained via `onDispatchError`. 45 tests against the
  OTel SDK test harness; docs: `docs/35-opentelemetry.md`.
- **Logging-stack bridges (G-32)**: new `ilogger-jul` and `ilogger-slf4j`
  artifacts. Inbound, `JulLogHandler` and the Logback `ILoggerAppender` turn
  third-party records into structured events (`action = prefix + logger name`,
  `source`, message/parameters in metadata, MDC as `mdc.*`, SLF4J 2 key/values
  as `kv.*`, original throwable preserved as `failure`). Outbound,
  `JulLogSink`/`Slf4jLogSink` route ILogger events into existing pipelines with
  full level mapping (including a configurable audit level) and SLF4J
  `ilogger.*` key/values for event id, action, entity, actor and correlation.
  Recursion is blocked across threads by the reserved `io.ilogger.bridge`
  logger prefix and on-thread by a shared bridge guard; `ilogger-slf4j`
  declares SLF4J/Logback `compileOnly`, so neither host library is forced onto
  consumers. 18 tests cover level mapping both ways, MDC/key-value context,
  throwable preservation, filtering and both-bridges-installed recursion.
  Docs: `docs/34-logging-bridges.md`.
- **Remote HTTP sink (G-29)**: new `ilogger-remote` artifact ships events to a
  collector in batched, gzip-compressed JSONL. `RemoteLogSink` appends to a
  durable `RemoteSpool` (`FileRemoteSpool` uses atomic rewrites and a byte
  cap), drains oldest-first, retries retryable failures with exponential
  backoff and an honored `Retry-After`, and removes lines only after a 2xx.
  Permanent 4xx responses and exhausted batches go to a `RemoteDeadLetter`.
  Every attempt carries `Idempotency-Key` derived from the batch content hash
  (stable across retries and restarts), while event ids in the payload allow
  per-event deduplication. A circuit breaker stops transport calls during an
  outage, per-attempt `headers` providers pick up rotated tokens, and the
  `RemoteTransport` SPI adapts JDK `HttpClient` (`JavaHttpTransport`), OkHttp
  or Ktor (documented). 19 MockWebServer tests cover success, 429/5xx,
  timeouts, spool recovery, duplicate suppression, dead letters and shutdown.
  Docs: `docs/33-remote-sink.md`.
- **Retention, legal hold and erasure (G-38)**: `RoomLogRetentionPolicy` adds
  per-category and per-entity keep windows (most specific rule wins; no rule +
  no default = retained forever) with construction-time validation.
  `RoomLogRetentionEnforcer` sweeps with a keyset cursor so held rows never
  stall a scan, deletes in chunks, reports removed/held counts, and exposes a
  `beforePurge` hook that receives exactly the rows about to be deleted (a
  throwing hook aborts the delete) for export-before-purge.
  `RoomLogLegalHoldRegistry` stores thread-safe `Event`/`Actor`/`Entity`/
  `Category`/`Action` holds matched against indexed columns, so they work with
  encrypted payloads; `RoomLogRetentionScheduler` accepts the enforcer for
  idle-time sweeps, and `roomLogs`/`RoomLogTarget` accept a hold registry so
  write-triggered purges honor it. `RoomLogErasure` implements actor/entity
  erasure with chunked deletes and writes one pseudonymous
  `compliance.erasure` audit event (kind, 16-hex SHA-256 target hash, counts,
  reason, request id — never the raw identifier or erased payload). Passing
  `payloadCipher` keeps the audit event encrypted. Documented in the new
  `docs/32-compliance.md`, including the file-sink and hash-chain tradeoffs.
- **Encryption at rest (G-37)**: `AesGcmCipher` encrypts stored text as
  self-describing `ilogenc1:<keyId>:<base64url(iv‖ciphertext‖tag)>` tokens —
  AES-GCM with a fresh 12-byte IV and 128-bit tag per record. `TextFileSink`
  gained an optional `cipher` parameter, so every stored line is opaque
  without the key and any modification fails decryption with
  `AEADBadTagException` instead of returning altered data; rotation, gzip and
  fsync compose unchanged. Keys come from an `EncryptionKeyProvider`, rotate
  through a `keysForReading` keyring (`keyId` in each token picks the right
  key), and `EncryptionKey` validates id and key size (16/24/32 bytes) at
  construction. `AesGcmRoomPayloadCipher` applies the same cipher to the Room
  `payload` column while indexed columns stay queryable; SQLCipher remains the
  whole-database option. Key loss consequences, rotation, tamper detection and
  the threat model are documented in the new
  `docs/31-encryption-at-rest.md`. Never ship default keys.
- **Tamper-evident audit trails (G-36)**: `HashChainSink` decorates any sink
  and stamps every forwarded event with four `ilogger.trail.*` metadata keys
  (`seq`, `prev`, `hash`, `keyId`) chaining it cryptographically to its
  predecessor; chain update and the downstream write are serialized under one
  mutex, so the wrapped sink observes exactly the chain order. `TrailSigner`
  ships `HmacTrailSigner` (HMAC-SHA256; Ed25519 documented as a JDK 15+ /
  Android API 33+ wrap), keys rotate via a `() -> SigningKey` provider and
  records carry the key id they were stamped with. `TrailVerifier.verify`
  recomputes stamps from record content (keyed mode) or checks chain structure
  only (no keys), accepts legitimate per-process segment restarts, and reports
  the first broken record with its index and reason. Stamping adds ~6 µs per
  event on the sink side (measured, `:benchmark` harness case 6); the feature
  is off by default. Threat model (including the trailing-segment-deletion
  gap and external anchoring) documented in the new `docs/30-integrity.md`.
  `JsonEventCodec` gained `decode(line): LogEvent` — the inverse of `encode` —
  so JSONL exports of a chained stream verify after a round trip
  (`docs/15-json-codec.md`); the canonical chain input mirrors the codec's
  structure-depth marker so stamps survive it.
- **Runtime redaction (G-35)**: an opt-in `RedactionPolicy` on
  `ILoggerBuilder.redaction` applies `RedactionRule`s to manual payloads —
  metadata values, change before/after values and actor attributes — at
  admission, before limits, sampling and sinks, so no sink ever sees the raw
  value. Built-in rules: `RedactionRules.secretKeys` (key-based, full key
  path), `email`, `phone`, `card` (Luhn-verified), and `bearer`; custom rules
  via the `RedactionRule` SPI; per-category rules via
  `rule(category, rule)`. Policies are immutable and thread-safe; a throwing
  rule is skipped by default or rejects the event with `STRICT_REJECT` under
  `strict = true`. Redaction runs before `EventLimits`, so masks count toward
  the byte budget and truncation never leaves a partial secret.
  `LogSafeValue` wrappers and top-level identifiers are never redacted.
  Docs: `docs/09-sensitive-data-protection.md`, `docs/26-configuration-reference.md`.
- **Event size and resource limits (G-39)**: an opt-in `EventLimits` policy on
  `ILoggerBuilder.limits` bounds every event at admission — `maxEventBytes`,
  `maxStringLength`, `maxEntries`, `maxActorAttributes` and `maxFailureFrames`.
  `LimitAction.REJECT` drops oversized events with a `STRICT_REJECT` drop
  report naming the violation; `LimitAction.TRUNCATE` binds oversized parts in
  place, marks them with `EventLimits.TRUNCATION_MARKER` and the
  `ilogger.truncated` metadata flag, and counts them in the new
  `DeliveryStats.truncated` field. Actions are never truncated. Separately,
  `JsonEventCodec` now cuts container recursion with a public
  `MAX_DEPTH_MARKER` (`"<max-depth>"`) instead of calling `toString()` on
  deep structures, so cyclic or pathologically nested metadata can no longer
  overflow the stack during encoding. Docs: `docs/24-limits-and-constraints.md`,
  `docs/14-sinks.md`, `docs/26-configuration-reference.md`.
- **Rate limiting and adaptive sampling (G-16)**: `TokenBucketRateLimiter`
  enforces per-level/action/category/actor token buckets (configurable rate and
  burst, bounded LRU key space, injectable `LogClock`), `AdaptiveSampler` sheds
  diagnostics proportionally once offered volume exceeds a target rate and
  recovers when the flood ends, and `CompositeSampler` combines independent
  policies. Audit events bypass both limiters unconditionally. `SamplingStats`
  reports considered/sampled/skipped volume, the live rate and active buckets;
  `SamplingContext` now carries the resolved actor. A simulated 100k-events/s
  diagnostic flood is bounded to the burst plus one window of refill while the
  audit stream is unaffected. Docs: `docs/17-sampling.md`.
- **Delivery quality gates and API docs (G-42)**: binary-compatibility-validator
  now guards the public ABI of all seven published JVM artifacts with committed
  `api/*.api` dumps (`apiCheck` runs from `check`), Kover enforces line-coverage
  floors for core/testing/room/serialization/ktor from `check`, and Dokka
  generates real Javadoc jars plus an `api-docs.zip` attached to every GitHub
  release. CI gained Windows/macOS module matrices (including the file-rotation
  suite), a real API 34 emulator job for both instrumented suites, a
  reproducible-archive check, and Dependabot updates for Gradle and Actions.
  Jar/zip tasks now strip timestamps and fix entry order, making archives
  byte-identical across rebuilds. Docs: new `docs/29-delivery-and-quality-gates.md`.
- **Reusable fakes, sink conformance suite and property tests (G-41)**:
  `ilogger-testing` now publishes `InMemoryLogSink` (the `TestLogSink` base),
  `FakeLogSink` (scriptable file/Room/HTTP stand-in with event, batch and
  payload capture plus transient, cadence and shutdown failure injection),
  `BlockingLogSink`, seeded deterministic `ChaoticLogSink`, and
  `RecordingDeadLetterSink`. `LogSinkConformance` is a JUnit 4 base class that
  validates any sink against batch ordering, empty batches, flush/close
  idempotency, retention across close, equal timestamps, the direct fast path
  and concurrent writers; it is adopted by `InMemoryLogSink`, `FakeLogSink`,
  `TextFileSink` and `RoomLogTarget`. Seeded codec/parser property tests cover
  JSON escaping, Unicode, control characters, unknown values, and Room JSONL
  export/import round-trips in CI.
- **Room query, observation and safety (G-31)**: `RoomLogFilter` (inclusive
  time range, level, action, entity, entity id, actor, outcome, correlation —
  all parameter-bound) plus `RoomLogPage` with newest/oldest order and stable
  `(ts, eventId)` tie-breaking. The shipped `RoomLogDao` gained raw-query
  hooks and typed helpers: `query`, Room-observed `observe`/`observeCount`,
  `count` and `distinctValues` over level/action/entity/entity id/actor/
  outcome/source/category/correlation facets — enough to build an audit viewer
  without custom SQL. `exportJsonLines`/`importJsonLines` round-trip rows
  losslessly (oldest-first paged export, batched `REPLACE` import with line
  numbers on malformed input), `RoomLogRetentionScheduler` runs retention on
  idle apps and catches up missed windows, `RoomLogTarget` gained
  `bindTimeoutMillis` plus an explicit `RoomLogTargetUnboundException` on
  timeout and on `close()` before `bind`, and `RoomLogPayloadCipher` encrypts
  the payload column while metadata stays queryable. `RoomLogSchema` now
  exposes `TABLE_VERSION` for future migrations; multi-process limits are
  documented. The reference app's audit screen streams rows through the new
  observed flow. Tests run against a real in-memory Room database (KSP-generated
  DAO) and cover filters, paging, counts, facets, observation, export/import,
  cipher round-trips, retention and bind failures.
- **Ktor client + server corrections (G-30)**: new `HttpAuditClient` client
  plugin records one `http.client.request` per send (method, host, path,
  status, duration, category `http`, source `ktor.client`), propagates an
  ambient `LogContext` correlation id as `X-Request-Id`, forwards or
  optionally generates W3C `traceparent`, rethrows transport failures after
  recording them with the structured cause and `transport = true`, and uses
  the injected `LogClock` for durations. `HttpAudit` now records through the
  suspend `ILogger.record` API (its non-suspend `ResponseSent` hook hands the
  event to the application scope without blocking the response), dedupes
  `CallFailed`/`ResponseSent` to one event per request via a per-call marker
  unless `failureEvents = BOTH` is configured, and gained a per-call
  `requestFilter` plus rate-based `sampling` that filters recording without
  losing correlation ids. Client and server events share the correlation id,
  so one outbound call and the work it triggers form a single trace.
- **Android module (G-28)**: new published `ilogger-android` artifact
  (minSdk 28). `LogcatSink` writes through `android.util.Log` (never
  `println`), maps levels to Logcat priorities, splits >3,800-character lines
  and supports per-event tags. `AndroidFileSink` wraps `TextFileSink` without
  implementing `DirectLogSink`, so every write — including batches and
  `INLINE` dispatch — hops to a dedicated IO dispatcher and cannot trip
  StrictMode or block the main thread. `AndroidLifecycleHooks` flushes on
  `ProcessLifecycleOwner` `ON_STOP`; `CrashBuffer` + `CrashHandoff` persist the
  recent-event ring (`fd.sync`, synthetic `android.crash` event) before the
  process dies; `AndroidActorProvider` supplies a persisted installation
  session actor with an in-memory user overlay; `ILoggerAndroid.install`
  wires all of it in one call. Optional WorkManager shipping ships as
  `ShippingSpool`/`ShippingSpoolSink`/`LogShippingWorker` with a
  `ShippingTransport` SPI (WorkManager is compile-only, batch send/ack,
  exponential backoff, at-least-once delivery). The reference app now uses the
  module for Logcat, lifecycle flush and file IO instead of app-local code,
  recovers pending crash reports at startup, and gained a missing
  `buildConfig = true` for AGP 9. JVM tests cover every adapter; instrumented
  tests compile for process-stop flush, SharedPreferences persistence, file
  output and the default Logcat writer (CI device job follows in G-42).
- **Stable diagnostics catalog and suppression (G-26)**: every processor
  message now carries a stable code (`ILG001`…`ILG065`), a severity and a
  copy-paste `Fix:` line. New warnings cover duplicate action declarations or
  emissions (`ILG062`), actions missing from an `@LogActions` registry
  (`ILG063`), explicit actor overrides selecting sensitive attributes
  (`ILG019`), unused `@LogDao` interfaces (`ILG053`), and two opt-in rules:
  `ilogger.require-actor` (`ILG020`) and `ilogger.sink-categories`
  (`ILG021`). Suppress per declaration or file with `@Suppress("ILGxxx")`
  (inherited by enclosing declarations and honored on `ILG`/`ALL`), or
  module-wide with `ilogger.suppress=ILG035,ILG061` / the Gradle DSL's
  `suppress` set. `docs/23-diagnostics-catalog.md` is now a code-indexed
  catalog asserted against the processor by `DiagnosticCatalogTest`, and the
  Gradle plugin ships `./gradlew iloggerDiagnostics` to print it with no
  compilation. New fixture modules `:ksp-diagnostics-warnings` and
  `:ksp-diagnostics-options` verify every rule, suppression and the
  compliant/silent cases verbatim.
- **Gradle plugin and BOM (G-25)**: `io.github.mohamed-dev-ui.ilogger`
  replaces three dependency declarations plus the KSP plugin with one line —
  it applies KSP, adds `ilogger-core`, `ilogger-annotations` and the processor
  at its own version, and exposes a type-safe `ilogger { }` DSL
  (`enabled`, `strict`, `verbose`, `domainPackages`, `schemaOutput`,
  `disabledVariants`). Disabling a variant only disables ILogger's processor
  in that KSP task, leaving other processors and variants untouched.
  `ilogger-bom` aligns every published module for manual and optional-module
  setups. `examples/gradle-plugin` applies only the plugin id and verifies the
  generated adapter at runtime.
- **DI-friendly extractors (G-24)**: annotation-named extractors now resolve
  through a per-logger `ExtractorRegistry` instead of being constructed once
  per generated wrapper. `LogMeta(..., inject = true)` declares an extractor
  that needs app services and must be supplied via
  `ILogger.create { extractor(Type::class) { ... } }` (factory or instance);
  other extractors keep a no-arg/object fallback. Resolution is lazy, cached,
  thread-safe and shared by every wrapper and event of that logger — register
  before the first event. Every call is validated against the log-safe value
  model, so a throwing or unsafe projection degrades to a `null` metadata
  value instead of breaking the operation. Suspend extractors remain
  unsupported by design: extraction stays synchronous on the caller's path.
- **Real `OMIT` and richer sensitivity contracts (G-23)**:
  `SensitiveHandling.OMIT` now means *emit no entry* — an explicit
  `LogMeta(..., sensitive = OMIT)` produces no key, converter or allocation,
  and `@LogConfig(sensitive = OMIT)` drops policy-less selections that touch
  `@LogSensitive` values while keeping the rest. Actor overrides are sanitized
  by the container policy (MASK/HASH record a projection; OMIT drops the
  actor); entity ids and `LogChange` still refuse sensitive sources.
  `@LogSensitive`/`@LogExclude` now target classes, properties, fields, value
  parameters, receivers and type uses, so annotated parameters and return
  types are enforced at the root of a chain. Extractor/adapter + OMIT is a
  compile error.
- **Action registry and schema manifest (G-22)**: `@LogActions(...)` on a
  constants object declares the module's canonical action codes (passed as
  annotation arguments because KSP cannot read property initializers) and
  generates `<Name>_ILoggerActions.actions`, a sorted, deduplicated list. The
  processor validates codes and writes a deterministic
  `META-INF/ilogger/schema.json` manifest at the end of the round sequence:
  every `(action, schemaVersion)` with its `schemaId`, entity, metadata keys,
  change fields and contributing sources, plus the declared codes. Reusing one
  action+version with different shapes is a build error; an identical shape
  under a bumped version, an undeclared action, or a single-segment registry
  code warns. `ilogger.schema-output=<path>` emits an extra copy for VCS/CI
  diffs.
- **Class-level defaults and opt-outs (G-21)**: `@LogConfig(category, level,
  dispatch, sampling, sensitive)` on an interface or open/abstract class
  provides defaults for every `@Log` member that leaves a field at its own
  default; explicit member values always win, and `@Log(useClassDefaults =
  false)` keeps a member at its literal values (so `AUDIT` survives a
  non-AUDIT class config). `sensitive = MASK|HASH` supplies the policy for
  `LogMeta` selections of `@LogSensitive` values that carry no explicit
  policy — the previous compile error becomes that policy (converter-bearing
  entries keep their own log-safe shape; entity ids and changes still
  refuse). `@NoLog` on a function excludes it from every wrapper entirely.
  Invalid `@LogConfig` values are rejected with container-scoped
  diagnostics.
- **Expanded safe value model and value adapters (G-20)**: metadata
  selections now accept `Char`, unsigned integers (`UInt`/`ULong`/`UShort`/
  `UByte`), `java.util.UUID`, `java.time.Instant`, `java.time.Duration`,
  `java.math.BigDecimal`/`BigInteger`, arrays (including primitive arrays),
  one-level maps with loggable keys/values, value classes over loggable
  underlying types, and `Result<T>` — each with a documented, tested
  canonical JSON form (the JSON codec renders containers structurally with a
  depth guard, unsigned integers as exact numbers, UUID/Instant/Duration as
  ISO-8601 strings). New `ValueAdapter<T>` (`render`) with two registration
  paths: `LogMeta(adapter = ...)` (instantiated once per generated wrapper,
  mutually exclusive with `extractor`) and `ILoggerBuilder.valueAdapter`
  (runtime registry applied to manual-recording metadata and changes at
  event materialization, exact-runtime-class lookup, throwing adapters
  degrade to null). New `ilogger.domain-packages` KSP option restricts
  entity inference to configured package prefixes; value classes are values,
  never entities.
- **Generics, `Result`/nullable returns and stream instrumentation (G-18)**:
  generic interfaces, classes and functions generate typed wrappers (type
  parameters and bounds preserved through the wrapper and its `logged()`
  extension; a type parameter is never inferred as an entity). `Result<T>`
  and nullable returns unwrap for entity inference and result chains
  (`entityId = "result.id"` on `Result<Task>` renders
  `result.getOrNull()?.id?.toString()`). `@Log` on Flow-returning functions
  instruments the stream lifecycle — START at subscription, SUCCESS on normal
  completion, FAILURE on stream error (rethrown verbatim, dispatch failures
  suppressed), cancellation SKIPPED by default or recorded as a cancelled
  FAILURE with `OnCancellation.LOG` (non-suspending `recordNow`+ASYNC) —
  while `result.*` selections on flows are rejected with a pointed
  diagnostic. The core library adds `loggedFlow`/`loggedChannel`/
  `loggedSequence` adapters with the same subscription/terminal/cancellation
  semantics and disabled-logger fast paths.
- **Classes and top-level function instrumentation (G-17)**: `@Log` now reaches beyond interfaces. Annotated members of `open`/`abstract` classes generate an `internal` subclass wrapper (`AuditedCounter_ILogger(logger, count)`) that forwards every superclass constructor parameter and overrides open members via `super`; top-level plain functions get a public `<Name>_ILogger` adapter object and top-level extension functions keep their receiver with the logger as the first parameter (`task.tagCount(logger)`). Final classes, `INNER` classes, private primary constructors and abstract members produce actionable diagnostics explaining the alternative (open/abstract the class, move the operation into an interface, or instrument the call site). Interfaces remain the recommended path; the compiler-plugin path for final classes is deferred.
- **Reserved-name codegen (G-19)**: parameters named `result`, `context`,
  `actor`, `logger`, `error`, or even `iloggerResult` no longer break
  generated wrappers — the previous compile-time rejection is gone. Source
  expressions resolve special heads (`result`, `actor`, `context.*`) before
  parameters, selections bind by parameter index, and generated locals rename
  themselves until collision-free while wrapper state is read through
  `this.iloggerDelegate` / `this.logger`. A `ReservedNamesRepository` fixture
  in the KSP suite locks the behavior in.
- **TestKit assertion DSL and deterministic draining**: `TestKit` now offers
  `assertLogged` / `assertNotLogged` (action, level, exact count, predicate),
  `eventsFor`, `awaitEvent { ... }`, `assertNoSensitive` (default credential
  and PII key vocabulary, masked/hashed values accepted, nested metadata
  scanned), `drain()` built on the flush barrier, canonical `golden(event)`
  snapshots and `advanceTimeBy`. `TestKit.create(workerDispatcher)` accepts a
  coroutines-test dispatcher so async pipelines can run on virtual time
  without `Thread.sleep`. The KSP suite includes a one-screen
  generated-wrapper snapshot test as the reference usage.
- **Backpressure-aware producer helpers**: `tryRecord` (never blocks, never
  throws, `false` on any drop), `recordOrDrop` (unit sugar), `isBacklogged`,
  `availableCapacity` and `hasSinks`. Full queues drop under `tryRecord` even
  when the logger is configured with `BLOCK`/`FAIL`, and inline admission
  requires a `DirectLogSink`. `DeliveryStats.bindings` now exposes a
  `BindingStats` entry per sink (category, pending depth, in-flight writes,
  accepted/delivered counters).
- **Performance regression gate**: `:benchmark` now covers generated `@Log`
  wrappers, compile-time metadata extraction, async/buffered admission,
  per-sink fan-out and rotating file-sink writes under JMH. A deterministic
  `BudgetGateTest` fails when the median disabled path exceeds 2 ns/op, async
  enqueue 1 µs/op or buffered audit admission 3 µs/op (15% noise tolerance);
  the `Benchmark — producer budgets` CI job runs it on Linux.
- **Strict runtime validation** (`strictMode = OFF | WARN | THROW`): manual
  events are validated at admission for log-safe value types, string lengths,
  entry counts and an approximate byte budget. Rejections surface through
  `onDrop` as `DropReason.STRICT_REJECT` and are counted in delivery stats;
  `LogSafeValue.of(...)` opts otherwise rejected objects in explicitly.
  The reference app runs `WARN` in debug builds.
- **Context-aware sampling**: `ContextualSampler` receives a `SamplingContext`
  with the real action, category, outcome and metadata; `ActionSampler`,
  `CategorySampler` and `RateLimitSampler` ship with injectable randomness.
  Correlated operations share one decision per action across
  START/SUCCESS/FAILURE, and `ILogger.isLoggable(level, action, category)`
  exposes the admission decision without allocating an event. The legacy
  two-argument `LogSampler` remains supported (it now receives the real
  action), and audit events are sampled only when `auditSampling = true`.
- **`LogEventDraft` recording API**: `record`, `recordNow` and `recordBlocking`
  now accept a draft whose only required field is `action`; every other field
  defaults to its event-level value. `LogEvent` defensively copies `metadata`
  and `changes` into unmodifiable containers, so mutating the caller's
  collections after recording cannot alter queued or persisted events.
- **`TextFileSink` durability and retention controls**: `fsyncEveryWrite`
  forces data to the storage device, `ioDispatcher` moves suspend writes off
  the caller, `Rotation.maxAgeMillis` adds lazy time-based rotation,
  `Rotation.gzip` compresses archives, and `Rotation.maxTotalBytes` caps the
  active file plus archives by pruning the oldest files first. Rotation,
  durability and retention are covered by `TextFileSinkTest` on Windows.
- **Cancellation-aware instrumentation**: `@Log(onCancellation =
  OnCancellation.LOG)` records a marked FAILURE event for cancelled suspend
  calls (`LogFailure.cancelled` / `isCancellation`). The default `SKIP` keeps
  cancellation silent. The original exception is always rethrown by identity,
  and a logging failure is attached as a suppressed exception instead of
  replacing it.
- **Multi-round, incremental KSP**: `@Log` interfaces generated by other
  processors are instrumented in a later round; unresolved symbols are
  deferred instead of failing eagerly; interface wrappers declare isolating
  dependencies; database facades stay aggregating. No-op builds regenerate
  nothing. Covered by a synthetic second processor in `:ksp-tests-generator`.
- **Test coverage for previously unverified integrations**:
  `ConsoleLogSink`, `LogContexts` (nesting, thread bridge, concurrency),
  chaos sinks (failing, close-failing, reentrant, concurrent), the Ktor
  plugin (`HttpAuditTest`) and `TestKit` now have golden and failure-path
  tests.

## [1.0.0] — 2026-10-21

### Added

- **Full reference app** (`:app`): a complete Android showcase covering every
  library usage case in one installable app — navigation drawer with nine
  screens (overview + live event feed, annotated task board, audit trail,
  file audit, protection, engine lab, test kit, copy-ready recipes, bench),
  production-shaped Hilt wiring (logger-before-database `RoomLogTarget`,
  category-routed sinks: live feed / Room 30-day retention / Logcat /
  rotated JSONL forensics, `onEvents` metrics with isolated failures),
  generated `@LogDao` facade with Hilt **and** Koin bindings, ambient
  actor/tenant/correlation scopes driven from the UI, and instrumented
  tests booting the real Hilt graph on device.
- **Triggers** (`ilogger-core`): `onEvent` / `onEvents` builder hooks backed by
  `TriggerSink`. A trigger receives finalized events (whole engine batches under
  `BUFFERED` dispatch) and cannot fail the producer: its exceptions are reported
  to `onError` and are never retried or propagated by the engine.
- `ilogger-room`: `RoomLogEntity` + `RoomLogDao` — the library-owned
  `ilogger_events` table. Add the entity to your `@Database` and wire it with
  `roomLogs(db.logDao())`: batching, JSON payloads, idempotent `REPLACE`
  inserts and optional retention sweeps are handled by the library. `RoomLogSchema`
  provides the DDL and `RoomLogSchema.migration(from, to)` for existing databases.
- `ilogger-room`: `RoomLogTarget` — a late-bound sink for logger-before-database
  setups; `bind(dao)` releases lifecycle events queued during `Room.build()`.
  `RoomLogRetention.keepDays(...)` adds opt-in retention.
- `examples/room` now persists every event through the shipped entity/DAO and
  verifies persistence, idempotent flushes and the migration contract against a
  real Room/SQLite database.
- **`@LogDao` database facade** (`ilogger-annotations` + `ilogger-ksp`): mark
  a DAO with `@LogDao` and the processor generates `<Database>_ILogged` plus
  `Database.logged(logger)`. Opted-in accessors return the already-wrapped
  `.logged(logger)` adapter, so call sites only wrap the database once;
  `@LogDao(enabled = false)` returns the raw DAO. Logged methods still need an
  explicit `@Log` — actions are never derived from method names. The existing
  per-DAO `.logged()` form is unchanged.
- `@LogDao(hilt = true)` generates an optional Hilt module in the consumer's
  compilation, providing the already-wrapped DAO as a `@Singleton`; no Hilt
  dependency is added to the library, and non-Hilt modules are unaffected.
  Duplicate bindings across multiple `@Database` classes are rejected with a
  compile-time diagnostic naming both owners.
- `@LogDao(koin = true)` generates `<Database>_ILogged_KoinModule`, whose
  `Module` resolves the database and logger from Koin and provides the wrapped
  DAO. Koin and Hilt remain optional: absent framework annotations produce a
  fixable diagnostic instead of a compile failure.
- `:app` has an Android instrumented test (`RoomLoggingInstrumentedTest`) that
  boots the Hilt graph on a device, emits through the generated repository
  wrapper, and verifies the event in the real Room file through both the
  injected DAO and a second connection.
- `ilogger-room`: `RoomLogSink` — a batching-aware sink that stores events in
  the application's **own** Room table (external `LogEntity`) through its DAO.
  Schema stays app-owned; the sink forwards each engine batch in one lambda
  call, propagates insert failures to the configured `ErrorMode`, and imports
  no Room types itself. The Room example (`examples/room`) now persists all
  events (DAO flows + database lifecycle) into a `log_events` table with a
  lossless JSON payload column (`JsonEventCodec`) and demonstrates read-back
  and retention purges.

### Changed

- The Android demo no longer calls `.setDriver(BundledSQLiteDriver())`; Room's
  default Android path owns the connection pool and needs no explicit driver.
  JVM examples keep the bundled driver, which Room requires outside Android.

## [0.1.0] — 2026-10-19

First production release.

### Changed

- **Published to Maven Central** via the vanniktech Maven Publish plugin
  (0.37.0) under group **`io.github.mohamed-dev-ui`** (the Central Portal
  namespace verified under the GitHub account; the old `io.ilogger` group
  was never verified and is unusable on Central). Version catalogs resolve
  `io.github.mohamed-dev-ui:<module>:<version>`.

### Added

- **Annotations** (`ilogger-annotations`): `@Log` (action, schemaVersion,
  entity, entityId, actor, level, category, dispatch, metadata, changes,
  before/onSuccess/onFailure, sampling, description), `@LogMeta`,
  `@LogChange`, `@LogSensitive` + `SensitiveHandling` (DENY/OMIT/MASK/HASH),
  `@LogExclude`, `@LogEntityId`.
- **Core engine** (`ilogger-core`): framework-independent `ILogger`
  (suspend `record()` / non-suspend `recordNow()`), sinks
  (console/text file/composite/custom), dispatch INLINE/ASYNC/BUFFERED,
  overflow DROP_OLDEST/DROP_NEWEST/BLOCK/FAIL, error modes
  DROP/RETRY/PROPAGATE, category routing with catch-all, injectable clock
  and id generators, correlation/causation ids, coroutine-scoped ambient
  `LogContext` (nestable actors, merged attributes), bounded memory, audit
  events never annotated-sampled.
- **KSP processor** (`ilogger-ksp`): compile-time `.logged()` wrapper
  generation — action/entity/id engineering, metadata value grammar
  (`result.*` / `param.*` / `'const'` / `actor.*` / `context.*`),
  sensitive handling, before/after change tracking, Room-shape awareness
  (insert/upsert/update/delete/query/transaction), `ilogger.enabled`
  disable switch, `ilogger.verbose` logging, `ilogger.expect-diagnostics`
  fixture mode. Fail-fast diagnostics in **What/Fix** format.
- **Test kit** (`ilogger-testing`): deterministic `TestKit` logger +
  `TestLogSink` with await, clock, id generators.
- **Integrations**: `ilogger-room` (database lifecycle audit: created,
  opened, destructive migration), `ilogger-serialization` (JSONL codec for
  durable sinks), `ilogger-ktor` (`HttpAudit` plugin + request-scoped
  ambient context + correlation propagation).
- **Examples**: basic, room, custom-sink (category routing + JSONL), server
  (Ktor), advanced-audit (backpressure, retry, sampling).
- **Demo app**: Android Compose app with in-app event viewer.
- **Benchmark**: JMH suite (kotlinx-benchmark 0.5.0) + deterministic
  harness; disabled path ≈ 1.4 ns/op, info inline 2.5 µs/op, audit record
  ≈ 2.8 µs/op, async enqueue ≈ 2.7 µs/op (JMH, JDK 24, Windows).
- **Verification**: 16 core tests, 15 generated-code behavior tests, 4
  codec tests, 6 automated negative-diagnostics fixtures.
- **Tooling**: CI (JVM matrix + Android APK, Maven-Local smoke) and
  tag-driven release workflow (GitHub Packages + release artifacts);
  Apache-2.0; README + 25-topic documentation set.

[2.0.0]: https://github.com/Mohamed-Dev-UI/ILogger/releases/tag/v2.0.0
[1.0.0]: https://github.com/Mohamed-Dev-UI/ILogger/releases/tag/v1.0.0
[0.1.0]: https://github.com/Mohamed-Dev-UI/ILogger/releases/tag/v0.1.0
