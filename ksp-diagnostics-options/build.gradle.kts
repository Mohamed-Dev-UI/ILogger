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

// Opt-in rules: audit attribution and sink routing. Compliant fixtures prove
// both rules stay silent when the operation supplies what the policy wants.
ksp {
    arg("ilogger.expect-diagnostics", "true")
    arg("ilogger.require-actor", "true")
    arg("ilogger.sink-categories", "audit,security")
}
