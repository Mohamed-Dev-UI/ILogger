# 29. Delivery pipeline & quality gates

Every change is gated by the same set of checks locally and in CI. Nothing in
this chapter is optional for a release pull request.

## Gate overview

| Gate | Command | Fails when | Runs in `check` |
|---|---|---|---|
| Tests | `.\gradlew.bat test` | Any test fails | yes |
| API compatibility | `.\gradlew.bat apiCheck` | A published binary signature changed and the API dump was not updated | yes |
| Coverage | `.\gradlew.bat koverVerify` | A module drops below its line-coverage floor | yes |
| Benchmark budgets | `.\gradlew.bat :benchmark:test --tests benchmark.BudgetGateTest` with `ILOGGER_BENCH_GATE=true` | A producer path exceeds its budget in all three measurements | dedicated CI job |
| Docs build | `.\gradlew.bat dokkaGenerate` | KDoc/Dokka generation fails | no (CI job) |
| Reproducible archives | see [Reproducible builds](#reproducible-builds) | Two builds of the same jar differ | CI job |

`build` depends on `check`, so an ordinary

```bash
./gradlew :ilogger-core:build
```

already runs the tests, the API check and the coverage floor for that module.

## Public API compatibility

Every published JVM artifact owns an API dump:

```text
ilogger-core/api/ilogger-core.api
ilogger-serialization/api/ilogger-serialization.api
ilogger-room/api/ilogger-room.api
ilogger-ktor/api/ilogger-ktor.api
ilogger-testing/api/ilogger-testing.api
ilogger-annotations/api/ilogger-annotations.api
ilogger-ksp/api/ilogger-ksp.api
```

The dumps are produced by
[binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator)
and define the module's ABI: public classes, signatures, annotations and
constants. `apiCheck` compares the current compilation against the committed
dump and fails the build on any difference — adding a method, changing a
parameter type, removing a top-level function, and so on.

When a change is **intentionally** source- or binary-incompatible:

1. Change the code.
2. Run `./gradlew apiDump`.
3. Review the diff under `api/` — it is the exact public-API delta.
4. Commit the dump with the code change.

For a compatible addition the diff only adds lines; for a breaking change,
call it out in the changelog and bump the version as described in
[actions & schema versioning](06-actions-and-schema-versioning.md).

`ilogger-android` is not covered by BCV: AGP 9 compiles Kotlin without the
legacy `org.jetbrains.kotlin.android` plugin that BCV hooks into. Treat its
public API with the same care and call out changes in review.

## Coverage floors

[Kover](https://github.com/Kotlin/kotlinx-kover) instruments the JVM test runs
and verifies line coverage in `check`:

| Module | Floor | Baseline at introduction |
|---|---:|---:|
| `ilogger-core` | 78% | 80.5% |
| `ilogger-testing` | 88% | 90.9% |
| `ilogger-room` | 82% | 85.5% |
| `ilogger-serialization` | 95% | 98.0% |
| `ilogger-ktor` | 95% | 98.8% |
| `ilogger-cli` | 80% | 96.8% |

Floors sit a few points below the measured baseline so ordinary refactors do
not trip them, while a real regression does. The authoritative values live in
the `coverageFloors` map in the root `build.gradle.kts`. `ilogger-cli` is a
runnable application, not a published library, so it has a coverage floor but
no API dump; its distribution and tests still run in CI.

Modules without a floor:

- `ilogger-ksp` — the processor runs in a Gradle worker during compilation and
  Kover cannot instrument across that boundary. Its behavior is proven by the
  generated-code fixtures in `:ksp-tests` and the `:ksp-diagnostics*` builds,
  which compile real consumers and assert the generated wrappers' behavior.
- `ilogger-annotations` — annotation-only, no executable code.
- `ilogger-android` — meaningful coverage needs instrumented tests on a
  device; those run in CI but do not feed the JVM coverage report.

Useful reports:

```bash
./gradlew :ilogger-core:koverHtmlReport   # build/reports/kover/html/index.html
./gradlew :ilogger-core:koverXmlReport    # machine-readable
./gradlew :ilogger-core:koverVerify       # just the gate
```

Raise a floor when coverage improves; lower one only with a reviewed reason
(e.g. a large generated or defensive branch that cannot be tested).

## API documentation

Dokka renders KDoc for every published artifact, including `ilogger-android`:

```bash
./gradlew dokkaGenerate
```

Output is written per module to `build/dokka/html/`. The release workflow
packages all module sites into `api-docs.zip` and attaches it to the GitHub
release; the same Dokka run supplies the Javadoc jars that Maven Central
requires. Documentation therefore ships with every `v*` tag and cannot drift
from the released sources.

KDoc comments are public API documentation: write them for consumers, and keep
`@param` / `@return` / `@throws` on public entry points.

## Reproducible builds

All archive tasks (jars, zips) are configured in the root build with:

```kotlin
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
```

Identical inputs (same commit, same JDK and tool versions) therefore produce
byte-identical archives. CI proves it by building two jars, hashing them,
rebuilding with `--rerun-tasks`, and diffing the hashes:

```bash
./gradlew :ilogger-core:jar :ilogger-serialization:jar -q
sha256sum ilogger-core/build/libs/ilogger-core-*.jar
./gradlew :ilogger-core:jar :ilogger-serialization:jar --rerun-tasks -q
sha256sum ilogger-core/build/libs/ilogger-core-*.jar
```

Reproducibility is scoped to one JDK major version: releases and CI both pin
JDK 17 (`jvmToolchain(17)`), but a jar produced by a different compiler
version is allowed to differ.

## CI jobs

`.github/workflows/ci.yml` runs on every push to `main` and every pull
request:

| Job | Runner | What it proves |
|---|---|---|
| `jvm` | ubuntu-latest | Full JVM build, KSP fixtures, diagnostics, six examples, Gradle-plugin TestKit, benchmark module, `apiCheck`, `koverVerify`, reproducible jars, publish smoke |
| `jvm-platforms` | windows-latest, macos-latest | Module tests and the `TextFileSink` rotation suite on real Windows/macOS filesystems |
| `android` | ubuntu-latest | Demo APK, Android unit tests, instrumented-test compilation, Dokka docs |
| `android-instrumented` | ubuntu-latest + API 34 emulator | Room/Hilt/Koin bindings and process-lifecycle behavior on a real Android runtime |
| `benchmark` | ubuntu-latest | Producer budgets; opt-in gate, 15% runner-noise allowance |

The Windows/macOS matrix matters most for file sinks: rotation uses
`Files.move`, directory listing and delete-while-open semantics that differ
from Linux. The matrix runs `:ilogger-core:test`, which includes every
`TextFileSinkTest` rotation case, plus the other framework modules.

The instrumented job runs `connectedDebugAndroidTest` for both the library and
the reference app. It is the only place where SharedPreferences persistence,
process-stop flushing, the default Logcat writer and Hilt/Koin Room bindings
execute against the Android platform.

## Dependency and security updates

`.github/dependabot.yml` opens weekly pull requests for:

- Gradle dependencies in the root build;
- Gradle dependencies in `ilogger-gradle-plugin` (a separate included build);
- GitHub Actions used by the workflows.

Kotlin, KSP and the Kotlin Compose plugin are grouped because they must move
together. KotlinPoet is pinned below 2.3 because newer releases ship Kotlin 2.4
metadata that the pinned Kotlin 2.2 compiler cannot read. Every dependency PR
runs the full CI pipeline before merging.

## Local pre-PR checklist

```bash
./gradlew build apiCheck koverVerify   # tests + compatibility + coverage
./gradlew dokkaGenerate                # docs still build
ILOGGER_BENCH_GATE=true ./gradlew :benchmark:test \
  --tests benchmark.BudgetGateTest     # only when touching the hot path
```

If `apiCheck` fails after an intended API change, run `./gradlew apiDump` and
include the updated dump. If `koverVerify` fails, add the missing tests or
explain the uncovered branch in review.
