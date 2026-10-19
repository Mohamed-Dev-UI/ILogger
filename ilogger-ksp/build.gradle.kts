plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // KSP API must ship with the processor: it is loaded on the consumer's
    // `ksp` processor classpath together with this jar.
    implementation(libs.ksp.api)

    // Processor-shipped codegen libraries; they travel with the processor on
    // the consumer's `ksp` classpath and never leak into consumer runtime.
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.junit)
}
