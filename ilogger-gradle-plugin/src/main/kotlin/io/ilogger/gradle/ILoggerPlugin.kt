package io.ilogger.gradle

import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.process.CommandLineArgumentProvider

/**
 * Applies KSP, adds `ilogger-core` / `ilogger-annotations` and the
 * `ilogger-ksp` processor, and forwards the `ilogger { }` DSL to processor
 * options.
 *
 * A single declaration is enough:
 * ```kotlin
 * plugins { id("io.github.mohamed-dev-ui.ilogger") version "2.0.0" }
 * ```
 */
public class ILoggerPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("ilogger", ILoggerExtension::class.java)

        project.pluginManager.apply(KSP_PLUGIN_ID)
        val ksp = project.extensions.getByType(KspExtension::class.java)

        ksp.arg(OPTION_ENABLED, extension.enabled.map { it.toString() })
        ksp.arg(OPTION_STRICT, extension.strict.map { it.toString() })
        ksp.arg(OPTION_VERBOSE, extension.verbose.map { it.toString() })
        ksp.arg(OPTION_DOMAIN_PACKAGES, extension.domainPackages.map { it.joinToString(",") })
        ksp.arg(
            OPTION_SCHEMA_OUTPUT,
            extension.schemaOutput.map { it.asFile.absolutePath }.orElse(""),
        )
        ksp.arg(OPTION_SUPPRESS, extension.suppress.map { it.joinToString(",") })
        ksp.arg(OPTION_REQUIRE_ACTOR, extension.requireActor.map { it.toString() })
        ksp.arg(OPTION_SINK_CATEGORIES, extension.sinkCategories.map { it.joinToString(",") })

        registerDiagnosticsTask(project)

        var dependenciesAdded = false
        fun addDependencies() {
            if (dependenciesAdded) return
            dependenciesAdded = true
            project.dependencies.add("implementation", "io.github.mohamed-dev-ui:ilogger-core:$ILOGGER_VERSION")
            project.dependencies.add("implementation", "io.github.mohamed-dev-ui:ilogger-annotations:$ILOGGER_VERSION")
            project.dependencies.add("ksp", "io.github.mohamed-dev-ui:ilogger-ksp:$ILOGGER_VERSION")
        }

        // JVM and Android (application/library) targets. KMP support arrives
        // with the multiplatform split; until then apply the processor manually
        // in KMP modules.
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { addDependencies() }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.android") { addDependencies() }
        project.pluginManager.withPlugin("com.android.application") { addDependencies() }
        project.pluginManager.withPlugin("com.android.library") { addDependencies() }

        disableSelectedVariants(project, extension)
    }

    /**
     * `./gradlew iloggerDiagnostics` prints the stable code catalog shipped
     * with the plugin (generated from `docs/23-diagnostics-catalog.md`), so
     * fixes are discoverable without leaving the build.
     */
    private fun registerDiagnosticsTask(project: Project) {
        project.tasks.register(DIAGNOSTICS_TASK) { task ->
            task.group = "help"
            task.description = "Prints the ILogger diagnostic codes, severities and fixes."
            task.doLast {
                val resource = ILoggerPlugin::class.java
                    .getResourceAsStream("/io/ilogger/gradle/diagnostics-catalog.txt")
                    ?: error("ILogger diagnostics catalog is missing from the plugin jar")
                resource.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split('|', limit = CATALOG_COLUMNS)
                        if (parts.size == CATALOG_COLUMNS) {
                            project.logger.lifecycle("${parts[0]} ${parts[1].uppercase()}: ${parts[2]}")
                            project.logger.lifecycle("  Fix: ${parts[3]}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Variant-specific opt-out. A task-level argument provider is merged after
     * the extension options by KSP, so it wins for that task only; other
     * processors sharing the KSP run keep working.
     */
    private fun disableSelectedVariants(project: Project, extension: ILoggerExtension) {
        project.tasks.withType(KspAATask::class.java).configureEach { task ->
            if (isVariantDisabled(task.name, extension.disabledVariants.get())) {
                task.commandLineArgumentProviders.add(
                    CommandLineArgumentProvider { listOf("$OPTION_ENABLED=false") },
                )
            }
        }
    }

    private companion object {
        const val KSP_PLUGIN_ID = "com.google.devtools.ksp"
        const val DIAGNOSTICS_TASK = "iloggerDiagnostics"
        const val CATALOG_COLUMNS = 4
        const val OPTION_ENABLED = "ilogger.enabled"
        const val OPTION_STRICT = "ilogger.strict"
        const val OPTION_VERBOSE = "ilogger.verbose"
        const val OPTION_DOMAIN_PACKAGES = "ilogger.domain-packages"
        const val OPTION_SCHEMA_OUTPUT = "ilogger.schema-output"
        const val OPTION_SUPPRESS = "ilogger.suppress"
        const val OPTION_REQUIRE_ACTOR = "ilogger.require-actor"
        const val OPTION_SINK_CATEGORIES = "ilogger.sink-categories"
    }
}

/** Matches an Android/JVM variant token (`debug`) or an exact KSP task name. */
internal fun isVariantDisabled(taskName: String, disabled: Set<String>): Boolean {
    if (disabled.isEmpty()) return false
    val variant = variantTokenOf(taskName)
    return disabled.any { it.equals(variant, ignoreCase = true) || it.equals(taskName, ignoreCase = true) }
}

/** `kspDebugUnitTestKotlin` → `debug`, `kspTestKotlin` → `test`, `kspKotlin` → `main`. */
internal fun variantTokenOf(taskName: String): String {
    if (!taskName.startsWith("ksp") || !taskName.endsWith("Kotlin")) return taskName
    var token = taskName.removePrefix("ksp").removeSuffix("Kotlin")
    var testOnly = false
    for (suffix in listOf("AndroidTest", "UnitTest", "Test")) {
        if (token.endsWith(suffix)) {
            token = token.removeSuffix(suffix)
            testOnly = token.isEmpty()
            break
        }
    }
    val resolved = when {
        token.isNotEmpty() -> token
        testOnly -> "test"
        else -> "main"
    }
    return resolved.lowercase()
}
