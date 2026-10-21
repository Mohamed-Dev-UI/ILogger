# 03. KSP setup

## Plugin versions

| Tool | Version | Notes |
|---|---|---|
| Kotlin | 2.2.10 | KSP2 (the K2 plugin) — no legacy shadow JAR |
| KSP | 2.3.12 | `com.google.devtools.ksp` plugin id |
| KotlinPoet | 2.2.0 | used by the processor internally, not by consumers |

KotlinPoet 2.2.0 is deliberately pinned: later releases ship Kotlin 2.4
metadata which the 2.2.x compiler cannot read.

## A JVM module

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":ilogger-annotations"))
    implementation(project(":ilogger-core"))
    ksp(project(":ilogger-ksp"))
}
```

Generated code lands in `build/generated/ksp/main/kotlin/` and is added to the
source set by the KSP plugin automatically.

## Processor options

| Option | Default | Meaning |
|---|---|---|
| `ilogger.enabled` | `true` | global kill switch; `false` makes the processor a no-op (fast path, zero files generated) |
| `ilogger.verbose` | `false` | log each generated file to the build output (one info line per interface/facade/DI module) |
| `ilogger.expect-diagnostics` | `false` | fixture mode for diagnostics test suites: fatal diagnostics become warnings, collected verbatim into `build/generated/ksp/main/resources/ilogger-diagnostics.txt`. Do **not** enable in production modules |

```kotlin
ksp {
    arg("ilogger.enabled", "true")
}
```

## Two processors on one module (Room + ILogger)

KSP runs multiple processors happily; ordering does not matter because they
touch disjoint classes:

```kotlin
ksp(project(":ilogger-ksp"))
ksp(libs.room.compiler)
```

The processor detects Room annotations (`@Insert`, `@Upsert`, `@Query`,
`@PrimaryKey`) **by qualified name only** — no Room dependency anywhere in
`ilogger-ksp`.

## Explicit API

Library modules compile with `-Xexplicit-api=strict`; generated wrappers are
`internal` classes plus a *public* `logged()` extension function in the
interface's own package, so the full contract shows in the public API of
consuming libraries.

## Common pitfalls

- Processor not applied? The `logged()` extension will not exist — you forgot
  the `ksp(project(":ilogger-ksp"))` configuration (not `implementation!`).
- Generated file missing: enable `ilogger.verbose=true` and check the KSP
  log lines (`e: [ksp] ...` for errors, everything else under
  `build/generated/ksp/`).
- KSP errors surface as `e: [ksp] <diagnostic>`; the diagnostic text links the
  offending declaration's file and line.

## Codegen vs. alternatives

There are other ways to attach audit instrumentation to interfaces. They are
not competitors at equal cost — this processor exists because they trade away
the guarantees:

| Criterion | `ilogger-ksp` (recommended) | Runtime reflection proxy | Hand-written decorator per interface |
|---|---|---|---|
| How it works | Generates delegating Kotlin at build time | `java.lang.reflect.Proxy`-style interception | You write `TaskRepository_ILogger` yourself |
| Compile-time validation of `@Log` | Yes — ambiguity is an error | No (annotations checked at runtime, errors appear late) | Only as good as your discipline |
| Runtime overhead | One delegated call, no reflection | Reflection on every call | Same as codegen |
| Dev speed (first feature) | Slower: plugin + annotation | Fastest: no build setup | Slow: boilerplate per interface |
| Dev speed (each next feature) | Fastest — annotate and build | Fast | Slow — every interface repeats |
| Debuggability | Generated Kotlin you can read/step into | Proxy magic, opaque stack frames | Fully manual |
| Multiplatform | JVM target today | Reflection limits | Manual |
| Recommended when | Default for audited interfaces | Not recommended | Only when codegen is unavailable |

**🏆 Recommended:** KSP codegen for anything that should be audited;
hand-written decorators only where KSP cannot run. Reflection proxies are
documented here for completeness, not endorsement — they move failures from
compile time to production. Manual recording remains available everywhere via
`ILogger.record` ([27-manual-instrumentation.md](27-manual-instrumentation.md)).
