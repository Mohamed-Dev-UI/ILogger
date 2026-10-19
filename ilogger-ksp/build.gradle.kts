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
        name.set("ilogger-ksp")
        description.set("KSP processor: annotation-driven, observable, audit-grade logging")
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
    // KSP API must ship with the processor: it is loaded on the consumer's
    // `ksp` processor classpath together with this jar.
    implementation(libs.ksp.api)

    // Processor-shipped codegen libraries; they travel with the processor on
    // the consumer's `ksp` classpath and never leak into consumer runtime.
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.junit)
}
