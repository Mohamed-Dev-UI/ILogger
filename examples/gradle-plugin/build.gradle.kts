plugins {
    alias(libs.plugins.kotlin.jvm)
    // The whole setup: KSP, ilogger-core, the processor and the options DSL.
    id("io.github.mohamed-dev-ui.ilogger")
}

group = "example"
version = "2.0.0"

kotlin {
    jvmToolchain(17)
}

ilogger {
    strict.set(true)
    domainPackages.add("example.plugin")
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(project(":ilogger-testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}
