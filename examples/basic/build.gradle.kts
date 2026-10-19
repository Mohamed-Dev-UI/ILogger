plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":ilogger-annotations"))
    api(project(":ilogger-core"))
    implementation(libs.kotlinx.coroutines.core)

    // Annotation processing happens at build time; the processor is never
    // needed at runtime after codegen.
    ksp(project(":ilogger-ksp"))
}

application {
    mainClass = "example.basic.MainKt"
}
