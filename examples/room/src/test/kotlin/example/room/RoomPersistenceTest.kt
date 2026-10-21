package example.room

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.core.withActor
import io.ilogger.room.RoomLogDao
import io.ilogger.room.RoomLogEntity
import io.ilogger.room.RoomLogRetention
import io.ilogger.room.RoomLogSchema
import io.ilogger.room.RoomLogTarget
import io.ilogger.room.roomLogs
import io.ilogger.room.toRoomLogEntity
import io.ilogger.room.withAuditCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun tempDbFile(): File =
    File(System.getProperty("java.io.tmpdir"), "ilogger-room-test-${System.nanoTime()}.db")

private fun event(id: String, action: String = id): LogEvent = LogEvent(
    id = id,
    timestampMillis = 1_700_000_000_000,
    level = LogLevel.AUDIT,
    action = action,
)

@Database(entities = [RoomLogEntity::class], version = 2, exportSchema = false)
abstract class MigrationDb : RoomDatabase() {
    abstract fun logDao(): RoomLogDao
}

class RoomPersistenceTest {

    @Test
    fun `lifecycle and audit events persist with their payload`() = runBlocking<Unit> {
        val file = tempDbFile()
        val logs = RoomLogTarget(retention = RoomLogRetention.keepDays(30))
        val logger = ILogger.create {
            sink(logs)
            dispatch = DispatchMode.BUFFERED
            batchSize = 4
            flushIntervalMillis = 1
        }
        val db = Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .withAuditCallback(logger, label = file.name)
            .build()
        logs.bind(db.logDao())

        withActor(Actor.user("u-42")) {
            db.taskDao().logged(logger).insert(TaskRow(title = "persisted"))
        }
        logger.close()

        val rows = db.logDao().recent(limit = 20)
        val context = "actions=${rows.map { it.action }}"
        assertTrue(context, rows.any { it.action == "room.database.opened" })
        assertTrue(context, rows.any { it.action == "task.created" })
        val created = rows.first { it.action == "task.created" }
        assertTrue(created.payload.contains("\"action\":\"task.created\""))
        assertEquals("u-42", created.actorId)

        db.close()
        file.delete()
    }

    @Test
    fun `database facade wraps opted-in daos and passes disabled ones through`() = runBlocking<Unit> {
        val file = tempDbFile()
        val logs = RoomLogTarget()
        val logger = ILogger.create {
            sink(logs)
            dispatch = DispatchMode.BUFFERED
            batchSize = 4
            flushIntervalMillis = 1
        }
        val db = Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        logs.bind(db.logDao())

        withActor(Actor.user("u-42")) {
            val daos = db.logged(logger)
            val id = daos.taskDao().insert(TaskRow(title = "facade"))
            assertEquals("facade", daos.untrackedTaskDao().titleOf(id))
        }
        logger.close()

        val actions = db.logDao().recent(limit = 20).map { it.action }
        assertTrue("actions=$actions", "task.created" in actions)
        assertTrue(
            "@LogDao(enabled = false) must return the raw DAO: $actions",
            "task.title.read" !in actions,
        )

        db.close()
        file.delete()
    }

    @Test
    fun `flush is idempotent on the event id`() = runBlocking<Unit> {
        val file = tempDbFile()
        val db = Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val logger = ILogger.create { roomLogs(db.logDao(), retention = null) }

        logger.recordNow(action = "app.started")
        logger.flush()
        logger.flush()
        logger.close()

        assertEquals(1, db.logDao().recent(limit = 10).size)

        db.close()
        file.delete()
    }

    @Test
    fun `migration creates a table Room accepts`() = runBlocking<Unit> {
        val file = tempDbFile()
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL("PRAGMA user_version = 1")
        }

        val db = Room.databaseBuilder<MigrationDb>(name = file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(RoomLogSchema.migration(1, 2))
            .build()

        db.logDao().insertAll(listOf(event("e1").toRoomLogEntity()))

        assertEquals(listOf("e1"), db.logDao().recent(limit = 10).map { it.eventId })

        db.close()
        file.delete()
    }
}
