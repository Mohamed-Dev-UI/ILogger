package io.ilogger.cli

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ilogger.core.LogEvent
import io.ilogger.core.crypto.TextCipher
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the `payload` column of a Room database containing
 * [io.ilogger.room.RoomLogEntity] without linking Room or the application's
 * generated database class.
 */
internal object RoomSource {

    const val DEFAULT_TABLE: String = "ilogger_events"

    private val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun read(path: Path, table: String, cipher: TextCipher?, onEvent: (LogEvent) -> Boolean): Int {
        if (!Files.isRegularFile(path)) throw CliException("database not found: $path")
        if (!TABLE_NAME.matches(table)) throw UsageException("invalid table name '$table'")

        return try {
            BundledSQLiteDriver().open(path.toAbsolutePath().toString()).use { connection ->
                ensureTable(connection, table, path)
                var rows = 0
                connection.prepare("SELECT `payload` FROM `$table` ORDER BY `ts`, `eventId`").use { statement ->
                    while (statement.step()) {
                        rows++
                        val payload = statement.getText(0)
                        val keepGoing = onEvent(EventDecoder.decode(payload, "row $rows", cipher))
                        if (!keepGoing) break
                    }
                }
                rows
            }
        } catch (error: CliException) {
            throw error
        } catch (error: UsageException) {
            throw error
        } catch (error: Exception) {
            throw CliException("cannot read $path: ${error.message ?: error::class.java.simpleName}", error)
        }
    }

    private fun ensureTable(connection: SQLiteConnection, table: String, path: Path) {
        connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.bindText(1, table)
            if (!statement.step()) {
                throw CliException(
                    "no table '$table' in $path; expected a Room database containing RoomLogEntity",
                )
            }
        }
    }

    fun looksLikeDatabase(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3") ||
            name.endsWith(".db3")
    }
}
