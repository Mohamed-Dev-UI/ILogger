// Top-level build file. Library modules define their own configuration; only plugin
// versions are declared here so every module resolves the same toolchain versions.
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlinx.kover) apply false
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.dokka) apply false
}

// Shared project identity. The group is the Maven Central namespace,
// verified at central.sonatype.com under the GitHub account Mohamed-Dev-UI.
allprojects {
    group = "io.github.mohamed-dev-ui"
    version = "2.0.0"
}

/**
 * Published JVM artifacts whose public surface is guarded by an API dump
 * under each module's `api` directory and whose KDoc is rendered by Dokka.
 */
val documentedModules = setOf(
    "ilogger-annotations",
    "ilogger-core",
    "ilogger-ksp",
    "ilogger-testing",
    "ilogger-room",
    "ilogger-serialization",
    "ilogger-ktor",
    "ilogger-remote",
    "ilogger-jul",
    "ilogger-slf4j",
    "ilogger-otel",
)

/**
 * `:ilogger-cli` is a runnable application, not a published library, so it has
 * no API dump; it is still covered by a Kover floor.
 */

/**
 * Minimum line coverage for each published runtime module. Kover enforces
 * these from `check` (and therefore from every CI build). Floors sit a few
 * points below the measured baseline: ordinary refactors pass, real
 * regressions fail. `ilogger-ksp` is intentionally absent: its behavior is
 * proven end-to-end by the generated-code fixtures in `:ksp-tests` and the
 * `:ksp-diagnostics` builds, which run the processor in a Gradle worker that
 * Kover cannot instrument. `ilogger-annotations` has no executable code and
 * `ilogger-android` needs a device for meaningful coverage.
 */
val coverageFloors = mapOf(
    "ilogger-core" to 78,
    "ilogger-testing" to 88,
    "ilogger-room" to 82,
    "ilogger-serialization" to 95,
    "ilogger-ktor" to 95,
    "ilogger-remote" to 82,
    "ilogger-jul" to 90,
    "ilogger-slf4j" to 85,
    "ilogger-otel" to 85,
    "ilogger-cli" to 80,
)

subprojects {
    // Byte-identical archives across machines: jar/zip entries use a stable
    // order and no file timestamps (G-42 reproducible builds).
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    val documented = name in documentedModules
    val coverageFloor = coverageFloors[name]
    if (!documented && coverageFloor == null) return@subprojects

    plugins.withId("org.jetbrains.kotlin.jvm") {
        if (documented) {
            // Committed API dumps turn accidental public-API changes into
            // failing PRs; refresh intentionally with `./gradlew apiDump`.
            apply(plugin = "org.jetbrains.kotlinx.binary-compatibility-validator")
            // `dokkaGenerate` produces the HTML bundle attached to every
            // release (Vanniktech also uses Dokka for the Javadoc jars).
            apply(plugin = "org.jetbrains.dokka")
        }
        if (coverageFloor != null) {
            // The report is written on demand; `koverVerify` runs from check.
            apply(plugin = "org.jetbrains.kotlinx.kover")
            extensions.configure<KoverProjectExtension> {
                reports {
                    verify {
                        rule {
                            minBound(coverageFloor)
                        }
                    }
                }
            }
        }
    }
}

/**
 * API documentation also covers the published Android artifact. Its coverage
 * lives in instrumented tests (no Kover) and BCV has no task for AGP 9's
 * built-in Kotlin, so the JVM API dumps are the compatibility gate.
 */
subprojects {
    if (name != "ilogger-android") return@subprojects
    plugins.withId("com.android.library") {
        apply(plugin = "org.jetbrains.dokka")
    }
}

/**
 * Library modules publish through the com.vanniktech.maven.publish plugin
 * (per-module `mavenPublishing { }`: Maven Central with GPG signing + the
 * built-in GitHub Packages repository when CI env vars are present).
 * This block registers only that env-gated GitHub Packages target, so
 * release runs keep uploading the same artifacts there.
 */
subprojects {
    if (name.startsWith("ilogger-")) {
        afterEvaluate {
            val ghActor = providers.environmentVariable("GITHUB_ACTOR").orNull
            val ghToken = providers.environmentVariable("GITHUB_TOKEN").orNull
            val ghRepo = providers.environmentVariable("GITHUB_REPOSITORY").orNull
            // `:ilogger-cli` is an application without a publishing extension.
            val publishing = extensions.findByType(PublishingExtension::class.java)
            if (ghActor != null && ghToken != null && ghRepo != null && publishing != null) {
                publishing.repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/$ghRepo")
                        credentials {
                            username = ghActor
                            password = ghToken
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Gradle-plugin example consumes the coordinates the plugin injects.
 * Substitute the monorepo projects so the sample needs no publish step.
 */
project(":examples:gradle-plugin") {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            for (module in listOf("annotations", "core", "ksp", "testing")) {
                substitute(module("io.github.mohamed-dev-ui:ilogger-$module"))
                    .using(project(":ilogger-$module"))
            }
        }
    }
}
