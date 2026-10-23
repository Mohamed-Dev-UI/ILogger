package io.ilogger.cli

import io.ilogger.core.crypto.EncryptionKey
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyCommandTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val hmacKey = ByteArray(32) { (it * 3).toByte() }
    private val events = listOf(
        sampleEvent(id = "event-1", action = "task.created"),
        sampleEvent(id = "event-2", action = "task.updated", timestampMillis = 1_700_000_001_000),
    )

    private fun chainedFile(): java.nio.file.Path =
        writeJsonl(folder.newFile("audit.jsonl").toPath(), chain(events, hmacKey))

    private fun keySpec(): String = "test=hex:${hmacKey.toHex()}"

    @Test
    fun `structural verification accepts an intact chain`() {
        val result = runCli("verify", chainedFile().toString())

        assertEquals(0, result.code)
        assertTrue(result.stdout, result.stdout.contains("OK 2 events verified"))
        assertTrue(result.stdout, result.stdout.contains("structural only"))
    }

    @Test
    fun `keyed verification accepts the right key`() {
        val result = runCli("verify", chainedFile().toString(), "--hmac-key", keySpec())

        assertEquals(0, result.code)
        assertTrue(result.stdout, result.stdout.contains("keyed: test"))
    }

    @Test
    fun `keyed verification detects modified content at its line`() {
        val path = chainedFile()
        val lines = Files.readAllLines(path)
        Files.write(path, lines.mapIndexed { index, line ->
            if (index == 1) line.replace("task.updated", "task.deleted") else line
        })

        val result = runCli("verify", path.toString(), "--hmac-key", keySpec())

        assertEquals(1, result.code)
        assertTrue(result.stdout, result.stdout.contains("FAIL line 2"))
        assertTrue(result.stdout, result.stdout.contains("stamp does not match"))
    }

    @Test
    fun `json verdict reports keyed counts and key ids`() {
        val result = runCli("verify", chainedFile().toString(), "--hmac-key", keySpec(), "--json")
        val verdict = Json.parseToJsonElement(result.stdout.trim()).jsonObject

        assertEquals(0, result.code)
        assertEquals(true, verdict["valid"]?.jsonPrimitive?.boolean)
        assertEquals(2, verdict["checked"]?.jsonPrimitive?.int)
        assertEquals(2, verdict["total"]?.jsonPrimitive?.int)
        assertEquals("keyed", verdict["mode"]?.jsonPrimitive?.content)
        assertEquals("test", verdict["keys"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun `json verdict points at the broken record`() {
        val path = chainedFile()
        val lines = Files.readAllLines(path)
        Files.write(path, lines.mapIndexed { index, line ->
            if (index == 1) line.replace("task.updated", "task.deleted") else line
        })

        val result = runCli("verify", path.toString(), "--hmac-key", keySpec(), "--json")
        val verdict = Json.parseToJsonElement(result.stdout.trim()).jsonObject

        assertEquals(1, result.code)
        assertEquals(false, verdict["valid"]?.jsonPrimitive?.boolean)
        assertEquals(1, verdict["brokenAt"]?.jsonPrimitive?.int)
        assertEquals(2, verdict["line"]?.jsonPrimitive?.int)
        assertTrue(verdict["reason"]?.jsonPrimitive?.content.orEmpty().contains("stamp does not match"))
    }

    @Test
    fun `structural mode ignores content changes but keyed mode does not`() {
        val path = chainedFile()
        val lines = Files.readAllLines(path)
        Files.write(path, lines.mapIndexed { index, line ->
            if (index == 0) line.replace("task.created", "task.deleted") else line
        })

        val structural = runCli("verify", path.toString())
        assertEquals(0, structural.code)
        assertFalse(structural.stdout.contains("FAIL"))

        val keyed = runCli("verify", path.toString(), "--hmac-key", keySpec())
        assertEquals(1, keyed.code)
    }

    @Test
    fun `unknown key id is reported`() {
        val result = runCli("verify", chainedFile().toString(), "--hmac-key", "other=hex:${hmacKey.toHex()}")

        assertEquals(1, result.code)
        assertTrue(result.stdout, result.stdout.contains("no key for id 'test'"))
    }

    @Test
    fun `encrypted chains verify with a decrypt keyring`() {
        val aesKey = EncryptionKey("prod", ByteArray(16) { it.toByte() })
        val path = writeLines(
            folder.newFile("encrypted.jsonl").toPath(),
            encryptedLines(chain(events, hmacKey), aesKey),
        )

        val missing = runCli("verify", path.toString())
        assertEquals(1, missing.code)
        assertTrue(missing.stderr, missing.stderr.contains("encrypted"))
        assertTrue(missing.stderr, missing.stderr.contains("--decrypt-key"))

        val result = runCli(
            "verify", path.toString(),
            "--hmac-key", keySpec(),
            "--decrypt-key", "prod=hex:${aesKey.bytes.toHex()}",
        )
        assertEquals(0, result.code)
        assertTrue(result.stdout, result.stdout.contains("OK 2 events verified"))
    }

    @Test
    fun `invalid line reports its line number`() {
        val path = writeLines(folder.newFile("broken.jsonl").toPath(), listOf("not json"))

        val result = runCli("verify", path.toString())

        assertEquals(1, result.code)
        assertTrue(result.stderr, result.stderr.contains("line 1"))
        assertTrue(result.stderr, result.stderr.contains("not a JSON ILogger event"))
    }

    @Test
    fun `missing file is a runtime failure`() {
        val result = runCli("verify", folder.root.resolve("nope.jsonl").toString())

        assertEquals(1, result.code)
        assertTrue(result.stderr, result.stderr.contains("file not found"))
    }
}
