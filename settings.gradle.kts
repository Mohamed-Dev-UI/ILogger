pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ILogger"

// Library modules (pure JVM, no framework dependencies)
include(":ilogger-annotations")
include(":ilogger-core")
include(":ilogger-ksp")
include(":ilogger-testing")

// Processor integration tests: sources compiled with KSP, behavior verified
// with JUnit (generated wrappers exercised through :ilogger-testing).
include(":ksp-tests")

// Negative diagnostics fixtures: intentionally invalid @Log usages that run
// the processor in fixture mode (errors → warnings + report file); a JUnit
// test asserts the `// EXPECTED:` markers verbatim.
include(":ksp-diagnostics")

// Framework integrations (optional modules)
include(":ilogger-room")
include(":ilogger-serialization")
include(":ilogger-ktor")

// Runnable examples
include(":examples:basic")
include(":examples:room")
include(":examples:custom-sink")
include(":examples:server")
include(":examples:advanced-audit")

// Overhead measurement (deterministic harness; see benchmark/README.md)
include(":benchmark")

// Applications
include(":app")
