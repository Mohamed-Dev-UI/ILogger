plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

// Publishing: Maven Central (primary target) + env-gated GitHub Packages (CI).
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // Central requires signed artifacts; signing only activates when key
    // material is provided (CI release runs pass it as a secret).
    if (hasProperty("signingInMemoryKey") || hasProperty("signing.keyId")) {
        signAllPublications()
    }
    pom {
        name.set("ilogger-annotations")
        description.set("Compile-time annotation contracts for ILogger")
        inceptionYear.set("2026")
        url.set("https://github.com/Mohamed-Dev-UI/ILogger")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Mohamed-Dev-UI")
                name.set("Mohamed")
                url.set("https://github.com/Mohamed-Dev-UI")
            }
        }
        scm {
            url.set("https://github.com/Mohamed-Dev-UI/ILogger")
            connection.set("scm:git:https://github.com/Mohamed-Dev-UI/ILogger.git")
            developerConnection.set("scm:git:https://github.com/Mohamed-Dev-UI/ILogger.git")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

// Annotations are consumed by source-level KSP processing; keep API metadata.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}
