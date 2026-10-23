package io.ilogger.room

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Table contract for [RoomLogEntity].
 *
 * [CREATE_TABLE_SQL] and [INDEX_SQL] match Room's expected schema exactly, which
 * lets [migration] pass Room's migration validation when an application adds the
 * log table to an existing database:
 *
 * ```kotlin
 * val db = Room.databaseBuilder<AppDatabase>(context, "app.db")
 *     .addMigrations(RoomLogSchema.migration(from = 4, to = 5))
 *     .build()
 * ```
 */
public object RoomLogSchema {

    /**
     * Version of the `ilogger_events` table contract, not of the application's
     * Room database. Bump it only when [RoomLogEntity] changes, and pair the
     * bump with a migration built from [migration] plus the necessary
     * `ALTER TABLE` statements.
     */
    public const val TABLE_VERSION: Int = 1

    /** Table name; the entity and DAO refer to this constant. */
    public const val TABLE: String = "ilogger_events"

    /** `CREATE TABLE` statement matching [RoomLogEntity]. */
    public val CREATE_TABLE_SQL: String = """
        CREATE TABLE IF NOT EXISTS `ilogger_events` (
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
    """.trimIndent()

    /** Index statements matching the entity's declared indices. */
    public val INDEX_SQL: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_ilogger_events_ts` ON `ilogger_events` (`ts`)",
        "CREATE INDEX IF NOT EXISTS `index_ilogger_events_level` ON `ilogger_events` (`level`)",
        "CREATE INDEX IF NOT EXISTS `index_ilogger_events_action` ON `ilogger_events` (`action`)",
    )

    /**
     * Migration that creates the log table (and its indices) between two
     * versions of the application's database.
     */
    public fun migration(startVersion: Int, endVersion: Int): Migration =
        object : Migration(startVersion, endVersion) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(CREATE_TABLE_SQL)
                INDEX_SQL.forEach { statement -> connection.execSQL(statement) }
            }
        }
}
