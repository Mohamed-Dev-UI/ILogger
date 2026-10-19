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
    api(project(":ilogger-ktor"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.cio)

    ksp(project(":ilogger-ksp"))
}

application {
    mainClass = "example.server.MainKt"
}
