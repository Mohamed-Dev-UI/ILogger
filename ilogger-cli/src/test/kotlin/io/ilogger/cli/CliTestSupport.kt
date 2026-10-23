package io.ilogger.cli

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ilogger.annotations.LogLevel
import io.ilogger.core.FieldChange
import io.ilogger.core.HashChainSink
import io.ilogger.core.HmacTrailSigner
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.LogSink
import io.ilogger.core.SigningKey
import io.ilogger.core.crypto.AesGcmCipher
import io.ilogger.core.crypto.EncryptionKey
import io.ilogger.serialization.JsonEventCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

internal data class CliResult(val code: Int, val stdout: String, val stderr: String)

internal fun runCli(vararg args: String): CliResult {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Cli.run(
        args,
        PrintStream(out, true, Charsets.UTF_8),
        PrintStream(err, true, Charsets.UTF_8),
    )
    return CliResult(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
}

internal fun sampleEvent(
    id: String = "event-1",
    action: String = "task.created",
    level: LogLevel = LogLevel.AUDIT,
    timestampMillis: Long = 1_700_000_000_000,
    metadata: Map<String, Any?> = emptyMap(),
    changes: List<FieldChange> = emptyList(),
    actor: io.ilogger.core.Actor? = null,
    entity: String? = null,
    entityId: String? = null,
    source: String? = null,
    category: String? = null,
    outcome: io.ilogger.core.Outcome = io.ilogger.core.Outcome.SUCCESS,
    failure: LogFailure? = null,
    actionVersion: Int = 1,
): LogEvent = LogEvent(
    id = id,
    timestampMillis = timestampMillis,
    level = level,
    action = action,
    actionVersion = actionVersion,
    actor = actor,
    entity = entity,
    entityId = entityId,
    metadata = metadata,
    changes = changes,
    source = source,
    category = category,
    outcome = outcome,
    failure = failure,
)

internal fun writeJsonl(path: Path, events: List<LogEvent>): Path {
    Files.write(path, events.map { JsonEventCodec.encode(it) })
    return path
}

internal fun writeLines(path: Path, lines: List<String>): Path {
    Files.write(path, lines)
    return path
}

internal fun chain(events: List<LogEvent>, key: ByteArray, keyId: String = "test"): List<LogEvent> {
    val captured = mutableListOf<LogEvent>()
    val collector = object : LogSink {
        override suspend fun write(event: LogEvent) {
            captured += event
        }
    }
    val sink = HashChainSink(collector, SigningKey(keyId, HmacTrailSigner(key)))
    runBlocking { events.forEach { sink.write(it) } }
    return captured
}

internal fun encryptedLines(events: List<LogEvent>, key: EncryptionKey): List<String> {
    val cipher = AesGcmCipher(key)
    return events.map { cipher.encrypt(JsonEventCodec.encode(it)) }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun createRoomDatabase(
    path: Path,
    payloads: List<String>,
    table: String = RoomSource.DEFAULT_TABLE,
) {
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare(
            """
            CREATE TABLE `$table` (
                `eventId` TEXT NOT NULL,
                `ts` INTEGER NOT NULL,
                `level` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `actionVersion` INTEGER NOT NULL,
                `entity` TEXT,
                `entityId` TEXT,
                `actorId` TEXT,
                `outcome` TEXT NOT NULL,
                `source` TEXT,
                `category` TEXT,
                `correlationId` TEXT,
                `causationId` TEXT,
                `payload` TEXT NOT NULL,
                PRIMARY KEY(`eventId`)
            )
            """.trimIndent(),
        ).use { statement -> statement.step() }

        payloads.forEachIndexed { index, payload ->
            connection.prepare("INSERT INTO `$table` VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .use { statement ->
                    statement.bindText(1, "event-$index")
                    statement.bindLong(2, 1_700_000_000_000 + index)
                    statement.bindText(3, "AUDIT")
                    statement.bindText(4, "task.created")
                    statement.bindLong(5, 1)
                    statement.bindNull(6)
                    statement.bindNull(7)
                    statement.bindNull(8)
                    statement.bindText(9, "SUCCESS")
                    statement.bindNull(10)
                    statement.bindNull(11)
                    statement.bindNull(12)
                    statement.bindNull(13)
                    statement.bindText(14, payload)
                    statement.step()
                }
        }
    }
}
