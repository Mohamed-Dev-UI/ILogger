package io.ilogger.room

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(entities = [RoomLogEntity::class], version = 1, exportSchema = false)
abstract class TestLogDatabase : RoomDatabase() {
    abstract fun logDao(): RoomLogDao
}

/** In-memory Room database for DAO query tests (real SQLite, no Android). */
internal class TestLogDatabaseHolder : AutoCloseable {

    private val database: TestLogDatabase = Room.inMemoryDatabaseBuilder<TestLogDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    val dao: RoomLogDao = database.logDao()

    override fun close() {
        database.close()
    }
}
