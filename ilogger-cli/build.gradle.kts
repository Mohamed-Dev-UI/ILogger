plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.ilogger.cli.MainKt")
    applicationName = "ilogger"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(project(":ilogger-core"))
    implementation(project(":ilogger-serialization"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Reads a Room (SQLite) database file without depending on Room itself:
    // the shipped entity's `payload` column is standard JSON text.
    implementation(libs.sqlite.bundled)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":ilogger-testing"))
}
