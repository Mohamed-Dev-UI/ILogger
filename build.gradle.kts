// Top-level build file. Library modules define their own configuration; only plugin
// versions are declared here so every module resolves the same toolchain versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Shared project identity. The group is the Maven Central namespace,
// verified at central.sonatype.com under the GitHub account Mohamed-Dev-UI.
allprojects {
    group = "io.github.mohamed-dev-ui"
    version = "0.1.0"
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
            if (ghActor != null && ghToken != null && ghRepo != null) {
                extensions.configure<PublishingExtension> {
                    repositories {
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
}
