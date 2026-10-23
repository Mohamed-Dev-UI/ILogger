package io.ilogger.gradle

import java.io.File
import java.util.Properties
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ILoggerPluginTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private fun fixture(buildScript: String) {
        projectDir.newFile("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        projectDir.newFile("build.gradle.kts").writeText(buildScript)
    }

    /** Plugin-under-test classpath plus KGP, which KSP loads eagerly. */
    private fun pluginClasspath(): List<File> {
        val metadata = javaClass.classLoader
            .getResourceAsStream("plugin-under-test-metadata.properties")!!
            .use { stream -> Properties().apply { load(stream) } }
        val plugin = metadata.getProperty("implementation-classpath")
            .split(File.pathSeparator)
            .map(::File)
        val kotlin = System.getProperty("ilogger.test.kotlinClasspath")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map(::File)
        return plugin + kotlin
    }

    private fun build(vararg arguments: String) = GradleRunner.create()
        .withProjectDir(projectDir.root)
        .withPluginClasspath(pluginClasspath())
        .withArguments(*arguments, "--stacktrace")
        .build()

    private val printArgumentsTask = """
        tasks.register("printArguments") {
            doLast {
                // Reflection keeps the fixture independent of KSP classloader
                // identity inside TestKit; `project.` avoids Task.extensions.
                val ksp = project.extensions.getByName("ksp")
                val args = ksp.javaClass.getMethod("getArguments").invoke(ksp) as Map<*, *>
                for (key in listOf(
                    "ilogger.enabled",
                    "ilogger.strict",
                    "ilogger.verbose",
                    "ilogger.domain-packages",
                    "ilogger.schema-output",
                    "ilogger.suppress",
                    "ilogger.require-actor",
                    "ilogger.sink-categories",
                )) {
                    println("ILG:" + key + "=" + args[key])
                }
            }
        }
    """.trimIndent()

    @Test
    fun `forwards the DSL to KSP arguments`() {
        fixture(
            """
            plugins { id("io.github.mohamed-dev-ui.ilogger") }

            ilogger {
                enabled.set(false)
                strict.set(true)
                verbose.set(true)
                domainPackages.add("com.acme")
                domainPackages.add("com.acme.shared")
                schemaOutput.set(layout.buildDirectory.file("reports/schema.json"))
                suppress.add("ILG035")
                suppress.add("ILG061")
                requireActor.set(true)
                sinkCategories.add("audit")
                sinkCategories.add("security")
            }

            $printArgumentsTask
            """.trimIndent(),
        )

        val output = build("printArguments").output

        assertTrue(output, output.contains("ILG:ilogger.enabled=false"))
        assertTrue(output, output.contains("ILG:ilogger.strict=true"))
        assertTrue(output, output.contains("ILG:ilogger.verbose=true"))
        assertTrue(output, output.contains("ILG:ilogger.domain-packages=com.acme,com.acme.shared"))
        assertTrue(output, output.contains("schema.json"))
        assertTrue(output, output.contains("ILG:ilogger.suppress=ILG035,ILG061"))
        assertTrue(output, output.contains("ILG:ilogger.require-actor=true"))
        assertTrue(output, output.contains("ILG:ilogger.sink-categories=audit,security"))
    }

    @Test
    fun `defaults keep processing enabled and strictness off`() {
        fixture(
            """
            plugins { id("io.github.mohamed-dev-ui.ilogger") }

            $printArgumentsTask
            """.trimIndent(),
        )

        val output = build("printArguments").output

        assertTrue(output, output.contains("ILG:ilogger.enabled=true"))
        assertTrue(output, output.contains("ILG:ilogger.strict=false"))
        assertTrue(output, output.contains("ILG:ilogger.verbose=false"))
        assertTrue(output, output.contains("ILG:ilogger.domain-packages="))
        assertTrue(output, output.contains("ILG:ilogger.schema-output="))
        assertTrue(output, output.contains("ILG:ilogger.suppress="))
        assertTrue(output, output.contains("ILG:ilogger.require-actor=false"))
        assertTrue(output, output.contains("ILG:ilogger.sink-categories="))
    }

    @Test
    fun `iloggerDiagnostics prints the stable code catalog`() {
        fixture("""plugins { id("io.github.mohamed-dev-ui.ilogger") }""")

        val output = build("iloggerDiagnostics").output

        assertTrue(output, output.contains("ILG001 ERROR: @Log applied to a declaration that cannot be wrapped"))
        assertTrue(output, output.contains("ILG062 WARNING: action code declared or emitted more than once"))
        assertTrue(output, output.contains("Fix: give distinct operations distinct codes"))
        assertTrue(output, output.contains("ILG065 WARNING"))
    }
}
