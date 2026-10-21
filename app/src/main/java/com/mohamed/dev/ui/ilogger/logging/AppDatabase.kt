package com.mohamed.dev.ui.ilogger.logging

import androidx.room.Database
import androidx.room.RoomDatabase
import io.ilogger.room.RoomLogDao
import io.ilogger.room.RoomLogEntity

/**
 * The application's own database. It owns the schema, its version and its
 * migrations; ILogger only supplies one entity ([RoomLogEntity]) and one DAO
 * ([RoomLogDao]) plus the [Task] example domain.
 *
 * Version 2 adds the `tasks` table to the log-only demo schema. A destructive
 * fallback is acceptable *for this sample app*; production apps add a normal
 * Room migration instead.
 */
@Database(
    entities = [Task::class, RoomLogEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): RoomLogDao
}
