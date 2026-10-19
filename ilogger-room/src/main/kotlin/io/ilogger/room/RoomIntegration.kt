package io.ilogger.room

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import io.ilogger.annotations.LogLevel
import io.ilogger.core.ILogger
import io.ilogger.core.Outcome

/**
 * Room integration: wires database lifecycle and SQL-query telemetry into an
 * [ILogger], alongside the annotation-driven DAO instrumentation produced by
 * `ilogger-ksp`.
 *
 * The processor detects Room annotations (`@Insert`, `@PrimaryKey`, ...) purely
 * by qualified name — this module only adds runtime hooks that Room itself
 * exposes. Deliberate privacy posture: nothing derived from SQL text or bound
 * arguments is ever recorded here.
 *
 * Note: Room's `setQueryCallback` (SQL telemetry) is Android-only upstream and
 * therefore intentionally not wrapped here, so this module stays
 * multiplatform-clean (JVM/Android).
 */

/**
 * Adds lifecycle audit events to a database builder chain.
 *
 * Events (in lifecycle order):
 * - `room.database.created`  ([LogLevel.AUDIT]) — first time the schema exists
 * - `room.database.opened`   ([LogLevel.INFO])  — every open
 * - `room.database.destructiveMigration` (WARN, `outcome = FAILURE`)
 *
 * @param label stable database identifier used as `entityId` of the events
 *   (file name is a good choice); `null` omits the id.
 */
public fun <T : RoomDatabase> RoomDatabase.Builder<T>.withAuditCallback(
    logger: ILogger,
    label: String? = null,
): RoomDatabase.Builder<T> = addCallback(
    object : RoomDatabase.Callback() {
        private val metadata: Map<String, Any?> =
            if (label == null) emptyMap() else mapOf("label" to label)

        override fun onCreate(connection: SQLiteConnection) {
            logger.recordNow(
                action = "room.database.created",
                level = LogLevel.AUDIT,
                entity = "Database",
                entityId = label,
                source = "androidx.room",
                metadata = metadata,
            )
        }

        override fun onOpen(connection: SQLiteConnection) {
            logger.recordNow(
                action = "room.database.opened",
                level = LogLevel.INFO,
                entity = "Database",
                entityId = label,
                source = "androidx.room",
                metadata = metadata,
            )
        }

        override fun onDestructiveMigration(connection: SQLiteConnection) {
            logger.recordNow(
                action = "room.database.destructiveMigration",
                level = LogLevel.WARN,
                entity = "Database",
                entityId = label,
                outcome = Outcome.FAILURE,
                source = "androidx.room",
                metadata = metadata,
            )
        }
    },
)

