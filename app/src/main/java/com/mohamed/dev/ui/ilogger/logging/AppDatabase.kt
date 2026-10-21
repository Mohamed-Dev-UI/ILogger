package com.mohamed.dev.ui.ilogger.logging

import androidx.room.Database
import androidx.room.RoomDatabase
import io.ilogger.room.RoomLogDao
import io.ilogger.room.RoomLogEntity

/**
 * Demo database: the only entity is the library-owned [RoomLogEntity].
 *
 * An application database normally also contains its domain entities; the demo
 * keeps those in memory so the wiring stays visible.
 */
@Database(entities = [RoomLogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): RoomLogDao
}
