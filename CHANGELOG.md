# Changelog

All notable changes to ILogger. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[SemVer](https://semver.org/) — the Kotlin DSL surface and artifact layout
remain stable within a major version.

## Unreleased

### Added

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

[0.1.0]: https://github.com/Mohamed-Dev-UI/ILogger/releases/tag/v0.1.0
