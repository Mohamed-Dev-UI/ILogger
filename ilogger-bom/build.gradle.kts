plugins {
    `java-platform`
    alias(libs.plugins.vanniktech.publish)
}

/**
 * Aligns every published ILogger artifact to one version:
 *
 * ```kotlin
 * implementation(platform("io.github.mohamed-dev-ui:ilogger-bom:2.0.0"))
 * implementation("io.github.mohamed-dev-ui:ilogger-core")
 * ```
 */
dependencies {
    constraints {
        api(project(":ilogger-annotations"))
        api(project(":ilogger-core"))
        api(project(":ilogger-ksp"))
        api(project(":ilogger-testing"))
        api(project(":ilogger-room"))
        api(project(":ilogger-serialization"))
        api(project(":ilogger-ktor"))
        api(project(":ilogger-remote"))
        api(project(":ilogger-android"))
        api(project(":ilogger-jul"))
        api(project(":ilogger-slf4j"))
        api(project(":ilogger-otel"))
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (hasProperty("signingInMemoryKey") || hasProperty("signing.keyId")) {
        signAllPublications()
    }
    pom {
        name.set("ilogger-bom")
        description.set("Bill of Materials: aligned versions for every ILogger module")
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
