// Top-level build file. Library modules define their own configuration; only plugin
// versions are declared here so every module resolves the same toolchain versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Shared project identity: one group/version for every published artifact.
allprojects {
    group = "io.ilogger"
    version = "0.1.0"
}

/**
 * Convention: every `ilogger-*` module publishes as `io.ilogger:<name>:<version>`
 * with the Java component (Kotlin classes + API deps). Evaluation happens per
 * module, so examples/app/benchmark are excluded by naming.
 */
subprojects {
    if (name.startsWith("ilogger-")) {
        pluginManager.apply("maven-publish")
        afterEvaluate {
            val moduleName = name // project name, captured outside the pom receiver
            extensions.configure<PublishingExtension> {
                publications.register<MavenPublication>("maven") {
                    from(components["java"])
                    pom {
                        name.set(moduleName)
                        description.set(
                            when (moduleName) {
                                "ilogger-ksp" -> "KSP processor: annotation-driven, observable, audit-grade logging"
                                "ilogger-annotations" -> "Compile-time annotation contracts for ILogger"
                                "ilogger-core" -> "Framework-independent logging & audit engine"
                                "ilogger-testing" -> "Deterministic test kit for ILogger consumers"
                                "ilogger-room" -> "Room database lifecycle integration for ILogger"
                                "ilogger-serialization" -> "JSON event codec for ILogger"
                                else -> "ILogger integration: $moduleName"
                            },
                        )
                        url.set("https://github.com/mohamed-dev/ilogger")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                                distribution.set("repo")
                            }
                        }
                        scm {
                            url.set("https://github.com/mohamed-dev/ilogger")
                            connection.set("scm:git:https://github.com/mohamed-dev/ilogger.git")
                            developerConnection.set("scm:git:https://github.com/mohamed-dev/ilogger.git")
                        }
                        developers {
                            developer {
                                id.set("mohamed-dev")
                                name.set("Mohamed")
                                url.set("https://github.com/mohamed-dev")
                            }
                        }
                    }
                }
            }
        }
    }
}
