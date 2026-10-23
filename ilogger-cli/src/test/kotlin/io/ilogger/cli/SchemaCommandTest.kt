package io.ilogger.cli

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SchemaCommandTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val manifest = """
        {
          "formatVersion": 1,
          "actions": [
            {
              "action": "task.created",
              "version": 1,
              "schemaId": "task.created",
              "entity": "Task",
              "metadata": ["title", "priority"],
              "changes": ["status"],
              "sources": ["TaskRepository.insert"]
            },
            {
              "action": "task.updated",
              "version": 2,
              "entity": "Task",
              "metadata": [],
              "changes": ["status"],
              "sources": []
            },
            {
              "action": "user.login",
              "version": 1,
              "sources": []
            }
          ],
          "declared": ["task.created", "task.updated", "user.login"]
        }
    """.trimIndent()

    private fun manifestFile(text: String = manifest, name: String = "schema.json"): java.nio.file.Path {
        val path = Files.createTempFile(folder.root.toPath(), "$name.", ".json")
        Files.writeString(path, text)
        return path
    }

    @Test
    fun `table output lists contracts and the summary`() {
        val result = runCli("schema", "--manifest", manifestFile().toString())

        assertEquals(0, result.code)
        val lines = result.stdout.lines()
        assertTrue(lines[0].startsWith("ACTION"))
        assertTrue(result.stdout, result.stdout.contains("task.created"))
        assertTrue(result.stdout, result.stdout.contains("v2"))
        assertTrue(result.stdout, result.stdout.contains("title, priority"))
        assertTrue(result.stdout, result.stdout.contains("formatVersion 1; 3 action contract(s)"))
        assertTrue(
            result.stdout,
            result.stdout.contains("declared: task.created, task.updated, user.login"),
        )
    }

    @Test
    fun `json output round-trips the manifest`() {
        val result = runCli("schema", "--manifest", manifestFile().toString(), "--format", "json")
        val root = Json.parseToJsonElement(result.stdout.trim()).jsonObject

        assertEquals(0, result.code)
        assertEquals(1, root["formatVersion"]?.jsonPrimitive?.int)
        assertEquals(3, root["actions"]?.jsonArray?.size)
        assertEquals(3, root["declared"]?.jsonArray?.size)
    }

    @Test
    fun `the positional argument can carry the manifest path`() {
        val result = runCli("schema", manifestFile().toString())

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("user.login"))
    }

    @Test
    fun `action filters accept exact values and prefixes`() {
        val exact = runCli("schema", "--manifest", manifestFile().toString(), "--action", "user.login")
        assertEquals(0, exact.code)
        assertTrue(exact.stdout.contains("user.login"))
        assertTrue(exact.stdout, exact.stdout.contains("1 action contract(s)"))

        val prefix = runCli("schema", "--manifest", manifestFile().toString(), "--action", "task.*")
        assertEquals(0, prefix.code)
        assertTrue(prefix.stdout, prefix.stdout.contains("2 action contract(s)"))
        assertTrue(prefix.stdout, prefix.stdout.contains("task.created"))
        assertTrue(prefix.stdout, prefix.stdout.contains("task.updated"))
        assertTrue(prefix.stdout, prefix.stdout.lines().none { it.startsWith("user.login") })

        val list = runCli(
            "schema", "--manifest", manifestFile().toString(),
            "--action", "task.created,user.*",
        )
        assertEquals(0, list.code)
        assertTrue(list.stdout, list.stdout.contains("2 action contract(s)"))
    }

    @Test
    fun `filtered json output keeps the declared list`() {
        val result = runCli(
            "schema", "--manifest", manifestFile().toString(),
            "--format", "json", "--action", "task.*",
        )
        val root = Json.parseToJsonElement(result.stdout.trim()).jsonObject

        assertEquals(0, result.code)
        assertEquals(2, root["actions"]?.jsonArray?.size)
        assertEquals(3, root["declared"]?.jsonArray?.size)
        assertEquals(1, root["formatVersion"]?.jsonPrimitive?.int)
    }

    @Test
    fun `missing schemaId falls back to action at version for duplicate detection`() {
        val path = manifestFile(
            """
            {
              "formatVersion": 1,
              "actions": [
                {"action": "task.updated", "version": 2},
                {"action": "task.updated", "version": 2}
              ]
            }
            """.trimIndent(),
            "fallback.json",
        )

        val result = runCli("schema", "--manifest", path.toString())

        assertEquals(1, result.code)
        assertTrue(result.stderr, result.stderr.contains("duplicate schemaId 'task.updated@v2'"))
    }

    @Test
    fun `duplicate schema ids are rejected`() {
        val path = manifestFile(
            """
            {
              "formatVersion": 1,
              "actions": [
                {"action": "a.one", "version": 1, "schemaId": "same"},
                {"action": "a.two", "version": 1, "schemaId": "same"}
              ]
            }
            """.trimIndent(),
            "duplicate.json",
        )

        val result = runCli("schema", "--manifest", path.toString())

        assertEquals(1, result.code)
        assertTrue(result.stderr, result.stderr.contains("duplicate schemaId 'same'"))
    }

    @Test
    fun `unsupported format versions are rejected`() {
        val path = manifestFile("""{"formatVersion": 2, "actions": []}""", "v2.json")

        val result = runCli("schema", "--manifest", path.toString())

        assertEquals(1, result.code)
        assertTrue(result.stderr, result.stderr.contains("unsupported schema manifest formatVersion 2"))
    }

    @Test
    fun `invalid json and missing actions are rejected`() {
        val broken = manifestFile("{", "broken.json")
        val invalid = runCli("schema", "--manifest", broken.toString())
        assertEquals(1, invalid.code)
        assertTrue(invalid.stderr, invalid.stderr.contains("invalid JSON"))

        val noActions = manifestFile("""{"formatVersion": 1}""", "empty.json")
        val missing = runCli("schema", "--manifest", noActions.toString())
        assertEquals(1, missing.code)
        assertTrue(missing.stderr, missing.stderr.contains("missing actions array"))
    }

    @Test
    fun `usage errors are reported with exit code 2`() {
        val path = manifestFile().toString()

        val noManifest = runCli("schema")
        assertEquals(2, noManifest.code)
        assertTrue(noManifest.stderr, noManifest.stderr.contains("requires --manifest"))

        val twice = runCli("schema", path, "--manifest", path)
        assertEquals(2, twice.code)
        assertTrue(twice.stderr, twice.stderr.contains("pass the manifest once"))

        val format = runCli("schema", "--manifest", path, "--format", "yaml")
        assertEquals(2, format.code)
        assertTrue(format.stderr, format.stderr.contains("--format must be table or json"))
    }

    @Test
    fun `missing manifest is a runtime failure`() {
        val result = runCli("schema", "--manifest", folder.root.resolve("nope.json").toString())

        assertEquals(1, result.code)
        assertTrue(result.stderr, result.stderr.contains("manifest not found"))
    }
}
