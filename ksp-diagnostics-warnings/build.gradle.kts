plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":ilogger-annotations"))
    implementation(project(":ilogger-core"))
    ksp(project(":ilogger-ksp"))
    testImplementation(libs.junit)
}

// Fixture mode: errors become warnings so the module compiles, and every
// emitted diagnostic lands in the report asserted by the test suite.
// ILG035 (suspicious names) is suppressed module-wide to prove the
// `ilogger.suppress` option.
ksp {
    arg("ilogger.expect-diagnostics", "true")
    arg("ilogger.suppress", "ILG035")
}
