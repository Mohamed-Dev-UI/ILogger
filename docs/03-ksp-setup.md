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
| `ilogger.enabled` | `true` | global kill switch; `false` makes the processor a no-op (fast path) |
| `ilogger.verbose` | `false` | log each processed interface to the build output |

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
