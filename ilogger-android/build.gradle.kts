plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "io.ilogger.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // LogcatSink defaults to android.util.Log; unit tests inject a writer,
        // but returnDefaultValues keeps unrelated framework references inert.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":ilogger-core"))
    // JSONL for crash reports and the shipping spool.
    implementation(project(":ilogger-serialization"))
    implementation(libs.kotlinx.coroutines.core)
    // ProcessLifecycleOwner; exposed because AndroidLifecycleHooks accepts a
    // Lifecycle in its public API.
    api(libs.androidx.lifecycle.process)

    // Optional shipping support: consumers who schedule the worker also add
    // WorkManager. The module compiles and links without it.
    compileOnly(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Publishing: Maven Central (primary target) + env-gated GitHub Packages (CI),
// matching every library module.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (hasProperty("signingInMemoryKey") || hasProperty("signing.keyId")) {
        signAllPublications()
    }
    pom {
        name.set("ilogger-android")
        description.set("Android integration: Logcat sink, lifecycle flush, crash handoff, file sink and session actor")
        inceptionYear.set("2026")
        url.set("https://github.com/Mohamed-Dev-UI/ILogger")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Mohamed-Dev-UI")
                name.set("Mohamed")
                url.set("https://github.com/Mohamed-Dev-UI")
            }
        }
        scm {
            url.set("https://github.com/Mohamed-Dev-UI/ILogger")
            connection.set("scm:git:https://github.com/Mohamed-Dev-UI/ILogger.git")
            developerConnection.set("scm:git:https://github.com/Mohamed-Dev-UI/ILogger.git")
        }
    }
}
