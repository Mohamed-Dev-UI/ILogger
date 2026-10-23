import org.gradle.api.publish.PublishingExtension

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

group = "io.github.mohamed-dev-ui"
version = "2.0.0"

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    website.set("https://github.com/Mohamed-Dev-UI/ILogger")
    vcsUrl.set("https://github.com/Mohamed-Dev-UI/ILogger")
    plugins {
        create("ilogger") {
            id = "io.github.mohamed-dev-ui.ilogger"
            displayName = "ILogger Gradle plugin"
            description = "Applies KSP and configures ILogger annotation processing."
            implementationClass = "io.ilogger.gradle.ILoggerPlugin"
            tags.set(listOf("logging", "audit", "ksp"))
        }
    }
}

/**
 * The plugin injects `io.github.mohamed-dev-ui:ilogger-*:<version>` coordinates;
 * the constant is generated from the plugin's own version so the two can never
 * drift.
 */
val generatedVersionDir = layout.buildDirectory.dir("generated/ilogger-version")
val generateILoggerVersion = tasks.register("generateILoggerVersion") {
    val versionString = project.version.toString()
    inputs.property("version", versionString)
    outputs.dir(generatedVersionDir)
    doLast {
        val file = generatedVersionDir.get().file("io/ilogger/gradle/ILoggerVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Generated from the plugin version — do not edit.
            |package io.ilogger.gradle
            |
            |internal const val ILOGGER_VERSION: String = "$versionString"
            |""".trimMargin(),
        )
    }
}

kotlin.sourceSets.getByName("main").kotlin.srcDir(generateILoggerVersion)

/**
 * Packages the diagnostics catalog with the plugin so `iloggerDiagnostics`
 * works offline. The source of truth stays `docs/23-diagnostics-catalog.md`,
 * which `:ilogger-ksp:test` asserts against the processor enum.
 */
val catalogSource = layout.projectDirectory.file("../docs/23-diagnostics-catalog.md")
val generatedCatalogDir = layout.buildDirectory.dir("generated/ilogger-catalog")
val generateILoggerCatalog = tasks.register("generateILoggerCatalog") {
    inputs.file(catalogSource)
    outputs.dir(generatedCatalogDir)
    doLast {
        val rowPattern = Regex("""^\|\s*`(ILG\d{3})`\s*\|\s*(error|warning)\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|$""")
        val rows = catalogSource.asFile.readLines().mapNotNull { line ->
            val match = rowPattern.matchEntire(line.trim()) ?: return@mapNotNull null
            match.groupValues.drop(1).joinToString("|")
        }
        require(rows.size >= 30) { "diagnostics catalog is missing its code table: ${catalogSource.asFile}" }
        val output = generatedCatalogDir.get().file("io/ilogger/gradle/diagnostics-catalog.txt").asFile
        output.parentFile.mkdirs()
        output.writeText(rows.joinToString("\n", postfix = "\n"))
    }
}

kotlin.sourceSets.getByName("main").resources.srcDir(generateILoggerCatalog)

// KSP needs the Kotlin Gradle plugin classes at runtime; TestKit fixtures get
// no implicit build classpath, so the KGP jars are injected explicitly.
val kotlinPluginClasspath: Configuration by configurations.creating

dependencies {
    // Bundled so one plugin declaration also provides the processor and KSP.
    implementation(libs.ksp.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
    kotlinPluginClasspath(libs.kotlin.gradle.plugin)
}

tasks.test {
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dilogger.test.kotlinClasspath=${kotlinPluginClasspath.asPath}")
        },
    )
}

// Publishing: Maven Central (primary target) + env-gated GitHub Packages (CI),
// matching every library module.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (hasProperty("signingInMemoryKey") || hasProperty("signing.keyId")) {
        signAllPublications()
    }
    pom {
        name.set("ilogger-gradle-plugin")
        description.set("Gradle plugin: KSP setup, dependencies and options for ILogger")
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

extensions.configure<PublishingExtension>("publishing") {
    val ghActor = providers.environmentVariable("GITHUB_ACTOR").orNull
    val ghToken = providers.environmentVariable("GITHUB_TOKEN").orNull
    val ghRepo = providers.environmentVariable("GITHUB_REPOSITORY").orNull
    if (ghActor != null && ghToken != null && ghRepo != null) {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/$ghRepo")
                credentials {
                    username = ghActor
                    password = ghToken
                }
            }
        }
    }
}
