plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":ilogger-annotations"))
    // Valid fixture wrappers (e.g. the Hilt-availability case) reference ILogger.
    implementation(project(":ilogger-core"))
    ksp(project(":ilogger-ksp"))
    testImplementation(libs.junit)
}

// The sources here are K2-valid but ilogger-invalid on purpose: the processor
// runs in fixture mode — diagnostics become warnings (module still compiles)
// and are written to build/generated/ksp/main/kotlin/ilogger-diagnostics.txt,
// which the test suite asserts against the `// EXPECTED:` markers.
ksp {
    arg("ilogger.expect-diagnostics", "true")
}
