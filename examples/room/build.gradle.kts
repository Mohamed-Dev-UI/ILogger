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
    api(project(":ilogger-room"))
    api(project(":ilogger-serialization"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.bundled)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Two processors on one module: Room codegen + ilogger instrumentation.
    ksp(project(":ilogger-ksp"))
    ksp(libs.room.compiler)
    // Test-only @Database (migration fixture) needs Room codegen as well.
    kspTest(libs.room.compiler)
}

application {
    mainClass = "example.room.MainKt"
}
