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
    // Annotations only: enough for the processor to consider each framework
    // available without pulling in an Android or DI plugin.
    implementation(libs.hilt.core)
    implementation(libs.koin.core)
    implementation(libs.room.runtime)
    ksp(project(":ilogger-ksp"))
    testImplementation(libs.junit)
}

// Same fixture mode as :ksp-diagnostics: duplicate bindings are reported as
// warnings and asserted verbatim, while the module still compiles.
ksp {
    arg("ilogger.expect-diagnostics", "true")
}
