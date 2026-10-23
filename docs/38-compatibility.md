# 38. Compatibility matrix

ILogger 2.0.0 is built and tested with the versions below. “Tested” means CI
compiles, runs the test suite and (for examples) executes the consumer with
that version. Newer patch/minor versions usually work, but are not claimed
until they are in this table.

## Build and runtime toolchain

| Component | Version | Notes |
|---|---|---|
| Kotlin | 2.2.10 | Compiler, stdlib and KSP processor |
| KSP | 2.3.12 | Processor API; the Gradle plugin injects the matching library version |
| KotlinPoet | 2.2.0 | Generated-code writer; cannot emit metadata newer than its Kotlin support |
| Gradle | 9.6.0 | Wrapper version; plugin tested with the same |
| JDK | 17 | `jvmToolchain(17)`; all published jars are Java 17 bytecode |
| Android Gradle Plugin | 9.4.1 | `ilogger-android` and the reference app |
| Android compileSdk | 37 | |
| Android minSdk | 28 | Android consumers of every JVM artifact |
| JUnit | 4.13.2 | `ilogger-testing` exposes JUnit 4 types (`LogSinkConformance`) |

## Runtime libraries exposed to consumers

| Library | Version | Scope | Used by |
|---|---|---|---|
| kotlinx-coroutines | 1.11.0 | runtime | `ilogger-core` and every module with suspend APIs |
| kotlinx-serialization-json | 1.11.0 | runtime | `ilogger-serialization`, `ilogger-room`, `ilogger-remote`, `ilogger-cli` |
| Room | 2.8.5 | `api` | `ilogger-room` (public callback/builder types) |
| Ktor server/client core | 3.6.0 | `api` | `ilogger-ktor`; add your engine separately |
| OpenTelemetry API | 1.43.0 | `compileOnly` | `ilogger-otel`; the host supplies the API + SDK |
| SLF4J API / Logback | 2.0.17 / 1.5.18 | `compileOnly` | `ilogger-slf4j`; the host supplies its logging backend |
| `java.util.logging` | JDK | built-in | `ilogger-jul` |
| SQLite bundled | 2.7.1 | CLI runtime, Room tests | `ilogger-cli` reads Room files; not a library dependency |
| OkHttp (tests only) | 4.12.0 | test | MockWebServer for `ilogger-remote` |

The BOM (`io.github.mohamed-dev-ui:ilogger-bom:2.0.0`) aligns every published
ILogger module; it does not constrain third-party libraries.

## Artifact / platform matrix

| Artifact | Kind | JVM 17 | Android 28+ | Non-JVM KMP |
|---|---|:---:|:---:|:---:|
| `ilogger-annotations` | jar | ✅ | ✅ | planned v2.0 |
| `ilogger-core` | jar | ✅ | ✅ | planned v2.0 |
| `ilogger-ksp` | jar (processor) | ✅ | ✅ | — |
| `ilogger-serialization` | jar | ✅ | ✅ | planned v2.0 |
| `ilogger-testing` | jar | ✅ | ✅ | planned v2.0 |
| `ilogger-room` | jar | ✅ | ✅ | — |
| `ilogger-ktor` | jar | ✅ | ✅ | — |
| `ilogger-remote` | jar | ✅ | ✅ | — |
| `ilogger-jul` | jar | ✅ | ✅ | — |
| `ilogger-slf4j` | jar | ✅ | ✅ | — |
| `ilogger-otel` | jar | ✅ | ✅ | — |
| `ilogger-android` | aar | — | ✅ | — |
| `ilogger-gradle-plugin` | Gradle plugin | ✅ | ✅ | — |
| `ilogger-bom` | platform | ✅ | ✅ | — |
| `ilogger-cli` | application | ✅ | — | — |

KMP details and the v2.0 plan: [37](37-kmp.md).

## Version policy

- **SemVer for the public API.** 1.x releases are additive; a binary
  incompatibility requires a major version. Binary compatibility is enforced
  by committed API dumps (`apiCheck`, see [29](29-delivery-and-quality-gates.md)).
- **Module versions move together.** Every published module and the Gradle
  plugin share one version. The plugin injects its own version for the
  runtime, annotations and processor, so a consumer normally declares nothing.
- **KSP and the library version must match.** A processor of version X
  generates adapters for runtime X. Mixing versions is unsupported (the
  generated code may use newer runtime APIs) and is diagnosed by the build.
- **Kotlin upgrades.** The processor targets KSP’s stable API and the
  generated code targets Kotlin 2.2. Upgrade Kotlin and KSP together, then run
  `:ksp-tests` and the diagnostics fixtures. Kotlin 2.4 metadata requires a
  KotlinPoet release that supports it; the 2.0.0 processor cannot emit it.
- **Deprecations** carry `@Deprecated` with a replacement for at least one
  minor release before removal in a major release.

## Known limits that affect compatibility

- Non-JVM KMP targets are not published ([37](37-kmp.md)).
- The KSP Gradle plugin wires JVM and Android targets only; KMP modules use
  manual KSP setup on the JVM/Android target.
- `TextFileSink` is single-process-writer; concurrent processes on one file
  are undefined by contract ([14](14-sinks.md)).
- Room's `setQueryCallback` is Android-source-only upstream and is not wrapped
  ([24](24-limits-and-constraints.md)).
- The CLI reads Room SQLite files directly; SQLCipher-encrypted databases are
  out of scope ([36](36-cli.md)).

## Verifying a combination

```bash
./gradlew check                       # tests, API dumps, coverage floors
./gradlew -p ilogger-gradle-plugin test   # plugin TestKit fixtures
./gradlew :examples:basic:run         # a real consumer, end to end
```

If you need a combination that is not in this table, open an issue with the
exact Kotlin/KSP/AGP/Gradle versions; new tested combinations are added here
when they are covered by CI.
