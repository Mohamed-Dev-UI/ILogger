package io.ilogger.cli

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.crypto.AesGcmCipher
import io.ilogger.core.crypto.EncryptionKey
import io.ilogger.serialization.JsonEventCodec
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplayCommandTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val created = sampleEvent(id = "event-1", action = "task.created")
    private val failed = sampleEvent(
        id = "event-2",
        action = "task.failed",
        level = LogLevel.WARN,
        timestampMillis = 1_700_000_001_000,
    )

    private fun jsonl(vararg events: LogEvent): java.nio.file.Path =
        writeJsonl(folder.newFile("audit.jsonl").toPath(), events.toList())

    private fun decodeIds(path: java.nio.file.Path): List<String> =
        Files.readAllLines(path).map {
            Json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.content.orEmpty()
        }

    @Test
    fun `console replay prints compact events and a summary`() {
        val result = runCli("replay", jsonl(created, failed).toString())

        assertEquals(0, result.code)
        assertEquals(2, result.stdout.trim().lines().size)
        assertTrue(result.stdout, result.stdout.contains("task.created"))
        assertTrue(result.stdout, result.stdout.contains("WARN  task.failed"))
        assertTrue(result.stderr, result.stderr.contains("replayed 2 events to console"))
    }

    @Test
    fun `quiet suppresses the summary`() {
        val result = runCli("replay", jsonl(created).toString(), "--quiet")

        assertEquals(0, result.code)
        assertTrue(result.stderr.isEmpty())
    }

    @Test
    fun `json console mode prints canonical lines`() {
        val result = runCli("replay", jsonl(created, failed).toString(), "--json")

        assertEquals(0, result.code)
        assertEquals(
            listOf(JsonEventCodec.encode(created), JsonEventCodec.encode(failed)),
            result.stdout.trim().lines(),
        )
    }

    @Test
    fun `filters and limit apply to replay`() {
        val result = runCli(
            "replay", jsonl(created, failed).toString(),
            "--action", "task.created", "--limit", "1",
        )

        assertEquals(0, result.code)
        assertEquals(1, result.stdout.trim().lines().size)
        assertTrue(result.stdout.contains("task.created"))
        assertTrue(result.stderr, result.stderr.contains("replayed 1 events"))
    }

    @Test
    fun `jsonl target appends and preserves ids and timestamps`() {
        val existing = sampleEvent(id = "existing", action = "task.archived")
        val target = writeJsonl(folder.newFile("target.jsonl").toPath(), listOf(existing))

        val result = runCli("replay", jsonl(created, failed).toString(), "--to", "jsonl:$target")

        assertEquals(0, result.code)
        assertEquals(listOf("existing", "event-1", "event-2"), decodeIds(target))
        val timestamps = Files.readAllLines(target).map {
            Json.parseToJsonElement(it).jsonObject["timestampMillis"]?.jsonPrimitive?.content
        }
        assertEquals(
            listOf(
                existing.timestampMillis.toString(),
                created.timestampMillis.toString(),
                failed.timestampMillis.toString(),
            ),
            timestamps,
        )
        assertTrue(result.stderr, result.stderr.contains("replayed 2 events to $target"))
    }

    @Test
    fun `overwrite truncates the target`() {
        val target = writeJsonl(
            folder.newFile("target.jsonl").toPath(),
            listOf(sampleEvent(id = "existing")),
        )

        val result = runCli(
            "replay", jsonl(created, failed).toString(),
            "--to", "jsonl:$target", "--overwrite",
        )

        assertEquals(0, result.code)
        assertEquals(listOf("event-1", "event-2"), decodeIds(target))
    }

    @Test
    fun `encrypt key writes an encrypted target`() {
        val key = EncryptionKey("prod", ByteArray(16) { (it + 1).toByte() })
        val target = folder.root.toPath().resolve("encrypted.jsonl")

        val result = runCli(
            "replay", jsonl(created).toString(),
            "--to", "jsonl:$target", "--overwrite",
            "--encrypt-key", "prod=hex:${key.bytes.toHex()}",
        )

        assertEquals(0, result.code)
        val line = Files.readString(target).trim()
        assertTrue(line, line.startsWith("ilogenc1:prod:"))
        val decoded = JsonEventCodec.decode(AesGcmCipher(key).decrypt(line))
        assertEquals(created.id, decoded.id)
        assertEquals(created.timestampMillis, decoded.timestampMillis)
    }

    @Test
    fun `rotates encrypted input onto a new key`() {
        val oldKey = EncryptionKey("old", ByteArray(16) { it.toByte() })
        val newKey = EncryptionKey("new", ByteArray(16) { (it + 7).toByte() })
        val input = writeLines(
            folder.newFile("encrypted-input.jsonl").toPath(),
            encryptedLines(listOf(created, failed), oldKey),
        )
        val target = folder.root.toPath().resolve("rotated.jsonl")

        val result = runCli(
            "replay", input.toString(),
            "--decrypt-key", "old=hex:${oldKey.bytes.toHex()}",
            "--to", "jsonl:$target", "--overwrite",
            "--encrypt-key", "new=hex:${newKey.bytes.toHex()}",
        )

        assertEquals(0, result.code)
        val lines = Files.readAllLines(target)
        assertEquals(2, lines.size)
        val cipher = AesGcmCipher(newKey)
        lines.forEach { line ->
            assertTrue(line, line.startsWith("ilogenc1:new:"))
            val event = JsonEventCodec.decode(cipher.decrypt(line))
            assertTrue(event.id, event.id in setOf("event-1", "event-2"))
        }
    }

    @Test
    fun `overwrite and encrypt key are rejected for the console target`() {
        val path = jsonl(created).toString()

        val overwrite = runCli("replay", path, "--overwrite")
        assertEquals(2, overwrite.code)
        assertTrue(overwrite.stderr, overwrite.stderr.contains("--overwrite only applies"))

        val encrypt = runCli("replay", path, "--encrypt-key", "k=hex:${ByteArray(16).toHex()}")
        assertEquals(2, encrypt.code)
        assertTrue(encrypt.stderr, encrypt.stderr.contains("--encrypt-key only applies"))
    }

    @Test
    fun `invalid targets are usage errors`() {
        val path = jsonl(created).toString()

        val unsupported = runCli("replay", path, "--to", "file:out.jsonl")
        assertEquals(2, unsupported.code)
        assertTrue(unsupported.stderr, unsupported.stderr.contains("--to must be 'console' or 'jsonl:<path>'"))

        val empty = runCli("replay", path, "--to", "jsonl:")
        assertEquals(2, empty.code)
        assertTrue(empty.stderr, empty.stderr.contains("requires a path"))
    }

    @Test
    fun `missing input is a runtime failure`() {
        val result = runCli("replay", folder.root.resolve("nope.jsonl").toString())

        assertEquals(1, result.code)
        assertTrue(result.stderr, result.stderr.contains("file not found"))
        assertTrue(result.stdout.isEmpty())
    }
}
