# 24. Limits & constraints

Deliberate constraints — each is a design decision, not a missing feature.

## What ILogger does not do

| Limit | Reason |
|---|---|
| No network shipping in core | transport lives in the optional `ilogger-remote` module; core stays dependency-free ([33](33-remote-sink.md)) |
| No runtime reflection anywhere | compile-time proof is the product; reflection would erase the guarantees |
| No database diffs | `LogChange` and `autoChanges` compare caller-provided objects only — never a stored row; DB diffs would be slow + wrong |
| No thread-local actor leak into coroutines | suspend vs non-suspend context split is documented and total ([05](05-event-lifecycle.md)) |
| No automatic module adoption | Room/Ktor/serialization are opt-in modules; core fabric stays framework-free |
| No unbounded buffers | every async pipeline has `bufferSize` and an explicit overflow policy |
| No silent entity-id guesses | ambiguity always produces a compile error with a Fix |
| No non-const action expressions | actions are public identifiers, defined once |

## Documented constants

Exact values implementations and consumers may rely on:

| Constant | Value | Source |
|---|---|---|
| `LogFailure.DEFAULT_STACK_FRAMES` | 8 | `EventDetails.kt` |
| `LogMasking.MASK` | `"***"` | `LogValueExtractor.kt` |
| `EventLimits` defaults | `maxEventBytes=65_536`, `maxStringLength=1_024`, `maxEntries=64`, `maxActorAttributes=32`, `maxFailureFrames=32`, `action=REJECT` | `EventLimits.kt` |
| `EventLimits.TRUNCATION_MARKER` | `"…[truncated]"` | appended to strings cut by the `TRUNCATE` policy |
| `EventLimits.TRUNCATED_KEY` | `"ilogger.truncated"` | metadata flag (value `true`) set on truncated events |
| `TrailKeys.RESERVED_PREFIX` | `"ilogger."` | metadata keys starting with it are reserved (integrity stamps, truncation flags); excluded from hash-chain canonical forms — see [30](30-integrity.md) |
| `HashChainSink.GENESIS_BYTES` | 32 zero bytes (base64url `"A".repeat(43)`) | the `ilogger.trail.prev` stamp of a segment start |
| `AesGcmCipher` token | `ilogenc1:<keyId>:<base64url(iv‖ciphertext‖tag)>`, 12-byte IV, 128-bit tag | `crypto/Encryption.kt`; keys are 16/24/32 bytes — see [31](31-encryption-at-rest.md) |
| `JsonEventCodec.MAX_DEPTH_MARKER` | `"<max-depth>"` | `JsonEventCodec.kt` |
| `LogMasking.HASH_LENGTH` | 24 | hex characters of the SHA-256 digest |
| Engine defaults | `bufferSize=4096` (per binding), `batchSize=128`, `flushIntervalMillis=250`, `sinkConcurrency=1`, `blockTimeoutMillis=5_000`, `closeTimeoutMillis=5_000`, `retryMaxAttempts=3`, `retryBaseBackoffMillis=50` | `ILoggerBuilder` |
| Retry backoff | `base * 2^attempt`, per-wait cap 10 s | `DefaultILogger` |
| Recursion depth guard (sync paths) | 32 nested dispatch calls | `DefaultILogger` |
| `TestKit` fixed clock | epoch `1_700_000_000_000` | `ilogger-testing` |
| `TestLogSink.awaitCount` timeout | 5_000 ms, 5 ms poll | `ilogger-testing` |
| Ktor defaults | `X-Request-Id` header; excludes `/health`, `/ready`, `/metrics` (prefix match) | `HttpLogConfig` |
| Room retention conversions | day = 86_400_000 ms, hour = 3_600_000 ms | `RoomLogRetention` |
| Room erasure audit | action `compliance.erasure`, category `compliance`, hash prefix 16 hex chars | `RoomLogErasure` — see [32](32-compliance.md) |
| Room table | `ilogger_events`, indexes on `ts`/`level`/`action` | `RoomLogSchema` |
| Remote sink defaults | `maxBatchSize=128`, `maxAttempts=3`, `retryBaseMillis=250`, `retryMaxMillis=10_000`, gzip on, circuit breaker 5 failures / 30 s | `RemoteLogSinkConfig` — see [33](33-remote-sink.md) |
| Remote batch key | first 32 hex chars of SHA-256 over the batch's encoded lines | `Idempotency-Key` header |
| `FileRemoteSpool` cap | 64 MiB (`maxBytes`) | `FileRemoteSpool` |
| Bridge logger prefix | `io.ilogger.bridge` (outbound SLF4J/JUL logger; inbound bridges ignore it) | `JulLogSink`/`Slf4jLogSink` — see [34](34-logging-bridges.md) |
| Bridge flush/admission timeout | 5,000 ms | `JulLogHandler`/`ILoggerAppender` |

## Event payload limits (`limits`)

`EventLimits` (opt-in via `ILoggerBuilder.limits`) runs at admission — after
runtime redaction, before sampling, strict validation, and dispatch:

| Limit | Default | Scope |
|---|---|---|
| `maxEventBytes` | 65,536 | Approximate budget: string characters + 8 bytes per scalar |
| `maxStringLength` | 1,024 | Any single string field (keys, values, entity, source, …) |
| `maxEntries` | 64 | Metadata, changes, and nested collections |
| `maxActorAttributes` | 32 | Actor attributes |
| `maxFailureFrames` | 32 | Retained stack frames of the failure summary |

Enforcement depends on `action`:

- **`REJECT`** (default) — the event is dropped before allocation of the
  pipeline work and reported via `onDrop` with `DropReason.STRICT_REJECT`;
  the violation message names the field. Use in debug builds to find
  oversized payloads.
- **`TRUNCATE`** — oversized parts are bound in place: strings are cut to
  the limit and suffixed with `EventLimits.TRUNCATION_MARKER`
  (`"…[truncated]"`), collections are capped to their entry limit, deep
  structures stop at depth 4, and a `metadata["ilogger.truncated"] = true`
  flag is set. Truncation is counted in `DeliveryStats.truncated`. Actions
  (public identifiers) are never truncated — an over-long action still
  rejects.

The JSON codec independently guards its own encoding depth
(`JsonEventCodec.MAX_DEPTH_MARKER`), so even unconfigured loggers cannot be
crashed by cyclic or pathologically nested metadata.

## Version & tooling pins

- Kotlin 2.2.10 / KSP 2.3.12 / KotlinPoet 2.2.0 (2.4 metadata unreadable by
  the 2.2.x compiler).
- Room 2.8.5 (KMP), sqlite-bundled 2.7.1 for the JVM Room example.
- Ktor 3.6.0 server core; AGP 9.4.1 for the demo app.

## Known platform limits

1. **Room's `setQueryCallback`** is Android-source-only upstream, hence not
   wrapped in `ilogger-room` (which stays JVM+Android clean).
2. **Room's JVM database builder** requires an absolute file path + bundled
   driver; in-memory variants exist for tests.
3. **Ktor `CallSetup`/`ResponseSent`/`CallFailed`** note: a thrown handler
   failure in the tested Ktor 3.6 test host reaches `CallFailed` only, so one
   failure event is recorded. The plugin does **not** deduplicate if an
   embedding lets both hooks observe the same call (e.g. a handler that
   catches its own exception, sends a 500, and the failed hook also runs) —
   that is G-30's job. Timestamps come from `System.currentTimeMillis()`
   rather than the logger clock; the audit event itself is timestamped by
   the configured clock.
4. **Negative diagnostics fixtures** run in the `:ksp-diagnostics` module
   behind a processor fixture mode (`ilogger.expect-diagnostics`) — verbatim
   message assertions. Multi-round behavior is covered separately: the
   `:ksp-tests-generator` module's second processor generates an `@Log`
   interface that the ILogger processor instruments in the next round.
   Incremental builds are verified through Gradle's up-to-date checks (no-op
   builds regenerate nothing), not through an in-process compile-testing
   harness.

## Roadmap candidates

- Full compile-testing harness (incremental-compilation round-trips beyond
  the fixture-mode assertions).
- `expect/actual` context for multiplatform `LogContexts` (current JVM/Android
  thread implementation is fine; iOS needs a worker-model variant) — part of
  the v2.0 KMP work ([37](37-kmp.md)).
- Optional GUI/Compose viewer on top of the CLI's read paths
  ([36](36-cli.md)).
- A Spring Boot starter ([GOALS.md](../GOALS.md), G-34) if demand justifies
  the maintenance surface.
