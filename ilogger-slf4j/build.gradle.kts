plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

// Publishing: Maven Central (primary target) + env-gated GitHub Packages (CI).
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (hasProperty("signingInMemoryKey") || hasProperty("signing.keyId")) {
        signAllPublications()
    }
    pom {
        name.set("ilogger-slf4j")
        description.set("SLF4J and Logback bridges for ILogger: appender into ILogger and ILogger sink into SLF4J")
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
            developerConnection.set("scm:git:ssh://git@github.com/Mohamed-Dev-UI/ILogger.git")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":ilogger-core"))

    // Opt-in host libraries: consumers already have SLF4J (and Logback when
    // they use the appender). Neither is added to the classpath by this
    // artifact, so adding the module without using it pollutes nothing.
    compileOnly(libs.slf4j.api)
    compileOnly(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":ilogger-testing"))
    testImplementation(libs.slf4j.api)
    testImplementation(libs.logback.classic)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}
