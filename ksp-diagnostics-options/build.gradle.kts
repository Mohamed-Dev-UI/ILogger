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
    testImplementation(project(":ilogger-testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Opt-in rules: audit attribution and sink routing. Compliant fixtures prove
// both rules stay silent when the operation supplies what the policy wants.
// Auto-capture is enabled module-wide so DEFAULT-inheriting calls are covered.
ksp {
    arg("ilogger.expect-diagnostics", "true")
    arg("ilogger.require-actor", "true")
    arg("ilogger.sink-categories", "audit,security")
    arg("ilogger.auto-changes", "all")
}
