plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mohamed.dev.ui.ilogger"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mohamed.dev.ui.ilogger"
        minSdk = 23
        targetSdk = 37
        versionCode = 4
        versionName = "2.1.0"

        // Boots HiltTestApplication; production modules still form the graph.
        testInstrumentationRunner = "com.mohamed.dev.ui.ilogger.logging.ILoggerTestRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // AGP 9 disables BuildConfig by default; the demo uses DEBUG to pick
        // strict-mode validation.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":ilogger-annotations"))
    implementation(project(":ilogger-core"))
    implementation(project(":ilogger-android"))
    implementation(project(":ilogger-room"))
    implementation(project(":ilogger-serialization"))
    // The in-app TestKit screen runs the same deterministic kit consumers use.
    implementation(project(":ilogger-testing"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(project(":ilogger-ksp"))
    ksp(libs.hilt.compiler)
    ksp(libs.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.koin.core)
    kspAndroidTest(project(":ilogger-ksp"))
    kspAndroidTest(libs.hilt.compiler)
    kspAndroidTest(libs.room.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
