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
    // Synthetic processor: generates an @Log interface for the multi-round test.
    ksp(project(":ksp-tests-generator"))

    testImplementation(project(":ilogger-testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

ksp {
    // Surfaces generated file names during the build (verified implicitly by
    // compilation succeeding when tests type-check against them).
    arg("ilogger.verbose", "true")
    // Entity inference whitelist: every fixture entity lives under ksp.test.
    arg("ilogger.domain-packages", "ksp.test.")
    // Extra copy of the action schema manifest outside the generated resources,
    // asserted by SchemaExportTest.
    arg("ilogger.schema-output", "${layout.buildDirectory.get().asFile.absolutePath}/ilogger/schema.json")
}
