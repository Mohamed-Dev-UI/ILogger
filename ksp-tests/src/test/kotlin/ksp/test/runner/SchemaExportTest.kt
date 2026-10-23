package ksp.test.runner

import java.io.File
import ksp.test.library.TaskActions
import ksp.test.library.TaskActions_ILoggerActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-22: the `@LogActions` registry compiles to a queryable object and the
 * processor folds every parsed operation into a deterministic
 * `META-INF/ilogger/schema.json` manifest.
 */
class SchemaExportTest {

    private val generatedManifest = File("build/generated/ksp/main/resources/META-INF/ilogger/schema.json")

    private val copiedManifest = File("build/ilogger/schema.json")

    @Test
    fun `generated registry lists declared actions sorted and distinct`() {
        val actions = TaskActions_ILoggerActions.actions

        assertEquals("registry must stay sorted", actions.sorted(), actions)
        assertEquals("registry must be deduplicated", actions.distinct(), actions)
        for (expected in listOf("task.created", "task.deleted", "cancel.default", "audit.shared", "generated.operation")) {
            assertTrue("registry is missing '$expected': $actions", expected in actions)
        }
    }

    @Test
    fun `manifest records action schemas with sorted arrays`() {
        assertTrue("manifest missing; run :ksp-tests:kspKotlin", generatedManifest.isFile)
        val manifest = generatedManifest.readText()
        assertTrue(manifest.startsWith("{\n  \"formatVersion\": 1,"))

        val entries = manifestEntries(manifest)
        assertTrue("expected manifest entries, got: $entries", entries.size >= 30)

        val created = entries.single { it.contains("\"action\": \"task.created\"") }
        assertTrue("version: $created", created.contains("\"version\": 1"))
        assertTrue("schemaId: $created", created.contains("\"schemaId\": \"task.created\""))
        assertTrue("entity: $created", created.contains("\"entity\": \"Task\""))
        assertTrue("metadata: $created", created.contains("\"metadata\": [\"priority\", \"title\"]"))
        assertTrue("changes: $created", created.contains("\"changes\": []"))
        assertTrue("sources: $created", created.contains("\"sources\": [\"TaskRepository.insert\"]"))

        val bumped = entries.single { it.contains("\"schemaId\": \"task.deleted@v2\"") }
        assertTrue("action: $bumped", bumped.contains("\"action\": \"task.deleted\""))
        assertTrue("version: $bumped", bumped.contains("\"version\": 2"))
        assertTrue("sources: $bumped", bumped.contains("\"sources\": [\"TaskRepository.inlineDelete\"]"))

        val sortedKeys = entries.map { entry ->
            val action = Regex("\"action\": \"([^\"]+)\"").find(entry)!!.groupValues[1]
            val version = Regex("\"version\": (\\d+)").find(entry)!!.groupValues[1].toInt()
            action to version
        }
        assertEquals("entries must be sorted by (action, version)", sortedKeys.sortedWith(compareBy({ it.first }, { it.second })), sortedKeys)
    }

    @Test
    fun `manifest folds multiple sources into one contract`() {
        val manifest = generatedManifest.readText()
        val shared = manifestEntries(manifest).single { it.contains("\"action\": \"audit.shared\"") }

        assertTrue(shared, shared.contains("\"entity\": \"Task\""))
        assertTrue(
            shared,
            shared.contains("\"sources\": [\"PrimaryAudit.record\", \"SecondaryAudit.record\"]"),
        )
    }

    @Test
    fun `declared list mirrors the constants object`() {
        val manifest = generatedManifest.readText()
        val declaredLine = manifest.lineSequence().single { it.trimStart().startsWith("\"declared\":") }
        val expected = TaskActions::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .sorted()
            .joinToString(", ", "  \"declared\": [", "]") { "\"$it\"" }

        assertEquals(expected, declaredLine)
        assertFalse("an undeclared action leaked into the manifest checks", expected.isEmpty())
    }

    @Test
    fun `schema output copy matches the generated resource`() {
        assertTrue("missing ${copiedManifest.path}; check ilogger.schema-output", copiedManifest.isFile)
        assertEquals(generatedManifest.readText(), copiedManifest.readText())
    }

    private fun manifestEntries(manifest: String): List<String> {
        val entries = mutableListOf<String>()
        var current: StringBuilder? = null
        for (line in manifest.lines()) {
            when {
                line == "    {" -> current = StringBuilder()
                line in setOf("    }", "    },") && current != null -> {
                    entries += current.toString()
                    current = null
                }
                current != null -> current.append(line.trim()).append('\n')
            }
        }
        return entries
    }
}
