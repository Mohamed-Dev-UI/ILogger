# 03. KSP setup

## Plugin versions

| Tool | Version | Notes |
|---|---|---|
| Kotlin | 2.2.10 | KSP2 (the K2 plugin) — no legacy shadow JAR |
| KSP | 2.3.12 | `com.google.devtools.ksp` plugin id |
| KotlinPoet | 2.2.0 | used by the processor internally, not by consumers |

KotlinPoet 2.2.0 is deliberately pinned: later releases ship Kotlin 2.4
metadata which the 2.2.x compiler cannot read.

## Gradle plugin (recommended)

One declaration replaces the plugin + three dependencies:

```kotlin
// module build.gradle.kts — JVM or Android
plugins {
    id("io.github.mohamed-dev-ui.ilogger") version "2.0.0"
}
```

The plugin applies KSP 2.3.12, adds `ilogger-core` and
`ilogger-annotations` to `implementation`, and `ilogger-ksp` to `ksp` at the
plugin's own version. Configuration is type-safe:

```kotlin
ilogger {
    enabled.set(true)                  // compile-time kill switch
    strict.set(true)                   // warnings become errors
    verbose.set(false)                 // one build line per generated file
    domainPackages.add("com.acme")     // entity inference allow-list
    schemaOutput.set(layout.projectDirectory.file("audit-schema.json"))
    disabledVariants.add("release")    // no codegen for that variant
    suppress.add("ILG035")             // module-wide diagnostic suppression
    requireActor.set(true)             // AUDIT ops must set @Log(actor = ...)
    sinkCategories.add("audit")        // categories that have a sink binding
    autoChanges.set(true)              // auto-diff eligible entity fields
}
```

| DSL property | Default | Processor option |
|---|---|---|
| `enabled` | `true` | `ilogger.enabled` |
| `strict` | `false` | `ilogger.strict` |
| `verbose` | `false` | `ilogger.verbose` |
| `domainPackages` | empty (built-in blacklist) | `ilogger.domain-packages` |
| `schemaOutput` | unset | `ilogger.schema-output` |
| `disabledVariants` | empty | task-level `ilogger.enabled=false` |
| `suppress` | empty | `ilogger.suppress` |
| `requireActor` | `false` | `ilogger.require-actor` |
| `sinkCategories` | empty (rule disabled) | `ilogger.sink-categories` |
| `autoChanges` | `false` | `ilogger.auto-changes` |

`disabledVariants` accepts variant tokens (`debug`, `release`, `test`) or
exact KSP task names. The opt-out is scoped to the matching KSP task, so other
processors in the same build and the other variants are untouched.
`ilogger.expect-diagnostics` is intentionally not exposed: it is a fixture
switch for this project's diagnostics tests, not an application option.

The plugin also registers `./gradlew iloggerDiagnostics`, which prints every
diagnostic code, severity and fix from
[the catalog](23-diagnostics-catalog.md). It needs no compilation and no
network access.

The plugin registers its toolchain dependencies through
`implementation`/`ksp`; use the [BOM](#bill-of-materials) when you want
consistent versions for the optional modules (`testing`, `room`,
`serialization`, `ktor`).

### Kotlin Multiplatform

Non-JVM KMP targets are not published in 1.x: the plugin wires JVM and Android
targets and emits a no-op otherwise. Apply `com.google.devtools.ksp` and the
three dependencies manually in the JVM/Android target of a KMP module; the JVM
target can still consume the generated wrappers. The recommended port/adapter
structure and the v2.0 plan are in [37-kmp.md](37-kmp.md).

## Manual setup (JVM module)

The manual wiring remains fully supported — it is the same configuration the
plugin performs, and the right choice inside KMP until the multiplatform
source split lands ([37](37-kmp.md)).

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

## Bill of materials

`ilogger-bom` aligns the versions of every published module, including the
optional integrations:

```kotlin
dependencies {
    implementation(platform("io.github.mohamed-dev-ui:ilogger-bom:2.0.0"))
    implementation("io.github.mohamed-dev-ui:ilogger-core")
    implementation("io.github.mohamed-dev-ui:ilogger-testing")
    implementation("io.github.mohamed-dev-ui:ilogger-room")
    implementation("io.github.mohamed-dev-ui:ilogger-serialization")
    implementation("io.github.mohamed-dev-ui:ilogger-ktor")
    ksp("io.github.mohamed-dev-ui:ilogger-ksp")
}
```

The BOM is a `java-platform` artifact: it contributes constraints only, so it
works in JVM and Android modules alike. The Gradle plugin already selects the
matching core/annotations/processor versions; the BOM is for the extra modules
and for teams that prefer explicit coordinates.

## Processor options

| Option | Default | Meaning |
|---|---|---|
| `ilogger.enabled` | `true` | global kill switch; `false` makes the processor a no-op (fast path, zero files generated) |
| `ilogger.strict` | `false` | promotes processor warnings (duplicate/undeclared actions, unused DAO, shape drift, and the opt-in actor/category rules when enabled) to errors — the Gradle plugin exposes this as `ilogger { strict.set(true) }` |
| `ilogger.verbose` | `false` | log each generated file to the build output (one info line per interface/facade/DI module) |
| `ilogger.expect-diagnostics` | `false` | fixture mode for diagnostics test suites: fatal diagnostics become warnings, collected verbatim into `build/generated/ksp/main/resources/ilogger-diagnostics.txt`. Do **not** enable in production modules |
| `ilogger.domain-packages` | *(empty)* | comma-separated package prefixes eligible for entity inference (e.g. `"com.acme.,com.acme.shared"`). When set, only types under these prefixes are inferred as `entity`; when empty, the default blacklist (stdlib, framework, logger-internal packages) applies |
| `ilogger.schema-output` | *(empty)* | absolute or module-relative path for an extra copy of the action manifest (`META-INF/ilogger/schema.json`). Useful for committing the contract to VCS or diffing it in CI; the generated resource is always written |
| `ilogger.suppress` | *(empty)* | comma-separated codes suppressed for the whole module, e.g. `"ILG035,ILG061"`. Per-symbol `@Suppress("ILGxxx")` works without this option |
| `ilogger.require-actor` | `false` | warns (ILG020) when an AUDIT operation has no explicit `@Log(actor = ...)` and depends on ambient attribution |
| `ilogger.sink-categories` | *(empty)* | comma-separated categories that have a sink binding. When non-empty, `@Log` categories outside the set warn (ILG021); empty disables the rule |
| `ilogger.auto-changes` | `off` | module-wide default for `@Log(autoChanges = AutoChanges.DEFAULT)`: `all` captures every eligible changed field, `off` records only explicit `LogChange` entries. Operations whose signature provides no before/after pair are skipped, so enabling it globally is safe; `@Log(autoChanges = OFF)` opts out per call |

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

## Multi-round processing and incremental builds

KSP invokes every processor once per *round*, and files generated in one round
become visible in the next. `ilogger-ksp` participates in every round:

- `@Log` declarations whose parameter/return or annotation types are still
  unresolved are **deferred** (returned to KSP) instead of failing eagerly.
  An interface generated by another processor is therefore instrumented in the
  round after it appears — no ordering requirement, no manual second pass.
- Each interface wrapper is generated exactly once per build and declares
  **isolating** dependencies: the interface file plus every source file whose
  declarations the generated code references. Editing an unrelated file does
  not regenerate it.
- Database facades and Hilt/Koin bindings declare **aggregating** dependencies
  because duplicate-binding detection spans every database in the compilation.
- A no-op build generates nothing new: the processor keeps per-build state
  (`processedInterfaces`, `processedDatabases`) so wrappers are never written
  twice in the same KSP run.

This is what makes ILogger safe to combine with other code generators (Room,
Hilt, Koin, your own processors): interfaces produced by them are ordinary
`@Log` inputs to ILogger in the next round.

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
