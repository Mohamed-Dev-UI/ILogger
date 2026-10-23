package io.ilogger.cli

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.crypto.AesGcmCipher
import io.ilogger.core.crypto.EncryptionKey
import io.ilogger.serialization.JsonEventCodec
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportCommandTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val created = sampleEvent(
        id = "event-1",
        action = "task.created",
        metadata = linkedMapOf("title" to "Write docs", "priority" to 2),
        actor = Actor.user("u-1", name = "Ada"),
        entity = "Task",
        entityId = "42",
        source = "TaskRepository.insert",
    )
    private val failed = sampleEvent(
        id = "event-2",
        action = "task.failed",
        level = LogLevel.WARN,
        timestampMillis = 1_700_000_001_000,
        failure = LogFailure("java.lang.IllegalStateException", "boom", null, emptyList()),
    )

    private fun jsonl(vararg events: LogEvent): java.nio.file.Path =
        writeJsonl(folder.newFile("audit.jsonl").toPath(), events.toList())

    private fun roomDatabase(
        name: String = "app.db",
        payloads: List<String>,
        table: String = RoomSource.DEFAULT_TABLE,
    ): java.nio.file.Path {
        val path = folder.newFile(name).toPath()
        createRoomDatabase(path, payloads, table)
        return path
    }

    @Test
    fun `csv export prints header rows and a summary`() {
        val result = runCli("export", jsonl(created, failed).toString())

        assertEquals(0, result.code)
        val lines = result.stdout.trim().lines()
        assertEquals(3, lines.size)
        assertEquals(EventFormat.csvHeader, lines[0])
        assertTrue(lines[1], lines[1].contains("task.created"))
        assertTrue(lines[1], lines[1].contains("Task,42,USER,u-1,Ada"))
        assertTrue(lines[2], lines[2].contains("java.lang.IllegalStateException,boom"))
        assertTrue(result.stderr, result.stderr.contains("exported 2 events (csv) to stdout"))
    }

    @Test
    fun `json export is a parseable array`() {
        val result = runCli("export", jsonl(created, failed).toString(), "--format", "json")

        assertEquals(0, result.code)
        val events = Json.parseToJsonElement(result.stdout.trim()).jsonArray
        assertEquals(2, events.size)
        assertEquals("event-1", events[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("task.failed", events[1].jsonObject["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun `json export of an empty result is an empty array`() {
        val result = runCli(
            "export", jsonl(created).toString(),
            "--format", "json", "--action", "nothing.matches",
        )

        assertEquals(0, result.code)
        assertEquals(0, Json.parseToJsonElement(result.stdout.trim()).jsonArray.size)
        assertTrue(result.stderr, result.stderr.contains("exported 0 events"))
    }

    @Test
    fun `jsonl export preserves canonical lines`() {
        val result = runCli("export", jsonl(created, failed).toString(), "--format", "jsonl")

        assertEquals(0, result.code)
        assertEquals(
            listOf(JsonEventCodec.encode(created), JsonEventCodec.encode(failed)),
            result.stdout.trim().lines(),
        )
    }

    @Test
    fun `filters and limit narrow the export`() {
        val result = runCli(
            "export", jsonl(created, failed).toString(),
            "--action", "task.*", "--level", "WARN", "--limit", "1", "--format", "jsonl",
        )

        assertEquals(0, result.code)
        assertEquals(1, result.stdout.trim().lines().size)
        assertTrue(result.stdout.contains("task.failed"))
        assertTrue(result.stderr, result.stderr.contains("exported 1 events"))
    }

    @Test
    fun `output writes a file in a new directory and keeps stdout empty`() {
        val target = folder.root.toPath().resolve("nested/audit.csv")

        val result = runCli("export", jsonl(created).toString(), "--output", target.toString())

        assertEquals(0, result.code)
        assertTrue(result.stdout.isEmpty())
        assertTrue(Files.isRegularFile(target))
        assertTrue(Files.readString(target).startsWith(EventFormat.csvHeader))
        assertTrue(result.stderr, result.stderr.contains(target.toString()))
    }

    @Test
    fun `reads a Room database by extension`() {
        val path = roomDatabase(payloads = listOf(
            JsonEventCodec.encode(created),
            JsonEventCodec.encode(failed),
        ))

        val result = runCli("export", path.toString(), "--format", "jsonl")

        assertEquals(0, result.code)
        assertEquals(
            listOf(created.id, failed.id),
            result.stdout.trim().lines().map {
                Json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.content
            },
        )
    }

    @Test
    fun `reads a custom Room table and rejects a missing one`() {
        val path = roomDatabase(
            name = "custom.db",
            payloads = listOf(JsonEventCodec.encode(created)),
            table = "audit_events",
        )

        val result = runCli("export", path.toString(), "--table", "audit_events", "--format", "jsonl")
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("task.created"))

        val missing = runCli("export", path.toString(), "--format", "jsonl")
        assertEquals(1, missing.code)
        assertTrue(missing.stderr, missing.stderr.contains("no table 'ilogger_events'"))
    }

    @Test
    fun `room payloads decrypt with a keyring`() {
        val aesKey = EncryptionKey("prod", ByteArray(16) { it.toByte() })
        val cipher = AesGcmCipher(aesKey)
        val path = roomDatabase(payloads = listOf(cipher.encrypt(JsonEventCodec.encode(created))))

        val missing = runCli("export", path.toString())
        assertEquals(1, missing.code)
        assertTrue(missing.stderr, missing.stderr.contains("--decrypt-key"))

        val result = runCli(
            "export", path.toString(),
            "--decrypt-key", "prod=hex:${aesKey.bytes.toHex()}",
        )
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("task.created"))
    }

    @Test
    fun `table only applies to Room input`() {
        val result = runCli("export", jsonl(created).toString(), "--table", "other")

        assertEquals(2, result.code)
        assertTrue(result.stderr, result.stderr.contains("--table only applies to Room input"))
    }

    @Test
    fun `explicit from overrides the file extension`() {
        val path = folder.newFile("weird.db").toPath()
        writeJsonl(path, listOf(created))

        val result = runCli("export", path.toString(), "--from", "jsonl", "--format", "jsonl")

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("task.created"))
    }

    @Test
    fun `unknown format and from values are usage errors`() {
        val path = jsonl(created).toString()

        val format = runCli("export", path, "--format", "yaml")
        assertEquals(2, format.code)
        assertTrue(format.stderr, format.stderr.contains("--format must be csv, json or jsonl"))

        val from = runCli("export", path, "--from", "csv")
        assertEquals(2, from.code)
        assertTrue(from.stderr, from.stderr.contains("--from must be auto, room or jsonl"))
    }

    @Test
    fun `missing input is a runtime failure`() {
        val result = runCli("export", folder.root.resolve("nope.jsonl").toString())

        assertEquals(1, result.code)
        assertTrue(result.stderr, result.stderr.contains("file not found"))
    }
}
