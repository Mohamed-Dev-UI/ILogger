plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":ilogger-core"))

    // Ktor server types are part of this module's public API surface
    // (ApplicationPlugin / ApplicationCall), so they are exposed to consumers.
    api(libs.ktor.server.core)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}
