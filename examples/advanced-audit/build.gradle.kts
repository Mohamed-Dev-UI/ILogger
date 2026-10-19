plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":ilogger-annotations"))
    api(project(":ilogger-core"))
    api(project(":ilogger-serialization"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass = "example.advanced.MainKt"
}
