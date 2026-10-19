plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.allopen)
    alias(libs.plugins.kotlinx.benchmark)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":ilogger-annotations"))
    api(project(":ilogger-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.benchmark.runtime)
}

// JMH needs @Benchmark/@State methods/fields non-final: open every annotated class.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

application {
    mainClass = "benchmark.MainKt"   // deterministic harness (quick validation mode)
}

benchmark {
    targets.register("main")        // JMH suite: ./gradlew :benchmark:benchmark
    // Validation profile — slower-by-default full profile is "benchmark".
    configurations.register("quick") {
        warmups = 1
        iterations = 2
        iterationTime = 1
        iterationTimeUnit = "s"
    }
}
