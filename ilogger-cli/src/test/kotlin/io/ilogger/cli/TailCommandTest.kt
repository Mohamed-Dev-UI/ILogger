package io.ilogger.cli

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.LogFailure
import io.ilogger.core.crypto.AesGcmCipher
import io.ilogger.core.crypto.EncryptionKey
import io.ilogger.serialization.JsonEventCodec
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TailCommandTest {

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

    private fun fileWith(vararg events: io.ilogger.core.LogEvent) =
        writeJsonl(folder.newFile("audit.jsonl").toPath(), events.toList())

    @Test
    fun `prints compact events with entity, actor, metadata and failure`() {
        val path = fileWith(created, failed)
        val result = runCli("tail", path.toString())

        assertEquals(0, result.code)
        val lines = result.stdout.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("2023-11-14T22:13:20Z AUDIT task.created"))
        assertTrue(lines[0], lines[0].contains("entity=Task:42"))
        assertTrue(lines[0], lines[0].contains("actor=user:u-1"))
        assertTrue(lines[0], lines[0].contains("outcome=SUCCESS"))
        assertTrue(lines[0], lines[0].contains("meta={priority=2, title=Write docs}"))
        assertTrue(lines[1], lines[1].contains("WARN  task.failed"))
        assertTrue(lines[1], lines[1].contains("failure=java.lang.IllegalStateException: boom"))
    }

    @Test
    fun `filters by level, action and limit`() {
        val path = fileWith(created, failed)

        val warn = runCli("tail", path.toString(), "--level", "WARN")
        assertEquals(0, warn.code)
        assertEquals(1, warn.stdout.trim().lines().size)
        assertTrue(warn.stdout.contains("task.failed"))

        val prefix = runCli("tail", path.toString(), "--action", "task.*", "--limit", "1")
        assertEquals(0, prefix.code)
        assertEquals(1, prefix.stdout.trim().lines().size)
        assertTrue(prefix.stdout.contains("task.created"))

        val limited = runCli("tail", path.toString(), "--limit", "2")
        assertEquals(2, limited.stdout.trim().lines().size)
    }

    @Test
    fun `json mode prints the original line`() {
        val path = fileWith(created, failed)
        val result = runCli("tail", path.toString(), "--json")

        assertEquals(0, result.code)
        assertEquals(
            listOf(JsonEventCodec.encode(created), JsonEventCodec.encode(failed)),
            result.stdout.trim().lines(),
        )
    }

    @Test
    fun `invalid lines fail with their line number`() {
        val path = folder.newFile("broken.jsonl").toPath()
        Files.writeString(path, "not json\n")

        val result = runCli("tail", path.toString())
        assertEquals(1, result.code)
        assertTrue(result.stderr.contains("line 1"))
        assertTrue(result.stderr.contains("not a JSON ILogger event"))
    }

    @Test
    fun `encrypted files require a decrypt key`() {
        val key = EncryptionKey("prod", ByteArray(16) { it.toByte() })
        val path = writeLines(folder.newFile("enc.jsonl").toPath(), encryptedLines(listOf(created), key))

        val missing = runCli("tail", path.toString())
        assertEquals(1, missing.code)
        assertTrue(missing.stderr.contains("encrypted"))
        assertTrue(missing.stderr.contains("--decrypt-key"))

        val hex = key.bytes.joinToString("") { "%02x".format(it) }
        val result = runCli("tail", path.toString(), "--decrypt-key", "prod=hex:$hex")
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("task.created"))
    }

    @Test
    fun `follow prints lines appended after startup`() {
        val path = fileWith(created)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<CliResult> {
                runCli(
                    "tail", path.toString(),
                    "--follow", "--limit", "2", "--poll-millis", "10",
                )
            }
            Thread.sleep(200)
            Files.writeString(
                path,
                JsonEventCodec.encode(failed) + "\n",
                StandardOpenOption.APPEND,
            )
            val result = future.get(10, TimeUnit.SECONDS)
            assertEquals(0, result.code)
            assertTrue(result.stdout.contains("task.created"))
            assertTrue(result.stdout.contains("task.failed"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `missing file is a runtime failure`() {
        val result = runCli("tail", folder.root.resolve("nope.jsonl").toString())
        assertEquals(1, result.code)
        assertTrue(result.stderr.contains("file not found"))
        assertFalse(result.stdout.isNotEmpty())
    }
}
