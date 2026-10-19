plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":ilogger-core"))

    // Room types are part of this module's public API surface (Callback /
    // Builder extensions), so they must be exposed to consumers.
    api(libs.room.runtime)

    testImplementation(libs.junit)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}
