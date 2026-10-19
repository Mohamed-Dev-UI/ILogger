plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":ilogger-annotations"))
    api(project(":ilogger-core"))
    implementation(libs.kotlinx.coroutines.core)

    // Runs the ilogger processor over this module's sources at build time.
    ksp(project(":ilogger-ksp"))

    testImplementation(project(":ilogger-testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

ksp {
    // Surfaces generated file names during the build (verified implicitly by
    // compilation succeeding when tests type-check against them).
    arg("ilogger.verbose", "true")
}
