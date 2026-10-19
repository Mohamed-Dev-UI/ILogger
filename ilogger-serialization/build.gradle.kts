plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":ilogger-core"))

    // JSON runtime only: the codec builds JsonElements directly and stores no
    // @Serializable classes, so the serialization compiler plugin is not needed.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}
