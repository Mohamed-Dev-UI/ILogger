# Changelog

All notable changes to ILogger. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[SemVer](https://semver.org/) — the Kotlin DSL surface and artifact layout
remain stable within a major version.

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
