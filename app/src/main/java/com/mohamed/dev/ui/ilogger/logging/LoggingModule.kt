package com.mohamed.dev.ui.ilogger.logging

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ilogger.annotations.DispatchMode
import io.ilogger.core.ILogger
import io.ilogger.core.sink.ConsoleLogSink
import io.ilogger.room.RoomLogDao
import io.ilogger.room.RoomLogRetention
import io.ilogger.room.roomLogs
import javax.inject.Singleton

/**
 * Recommended production wiring: the application still owns its database, and
 * the logger only receives its [RoomLogDao].
 *
 * Graph direction is `AppDatabase -> ILogger -> consumers`, with no cycle:
 * if lifecycle events are wanted later, provide a `RoomLogTarget` first, build
 * the logger with `sink(target)`, and call `target.bind(db.logDao())` inside
 * the database provider (see docs/21).
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        // No explicit driver: Room's Android default path manages its own
        // connection pool and is the thread-safe choice for an app database.
        Room.databaseBuilder<AppDatabase>(context, "ilogger-demo.db")
            .build()

    @Provides
    @Singleton
    fun logger(db: AppDatabase, buffer: EventBuffer): ILogger = ILogger.create {
        dispatch = DispatchMode.ASYNC
        sink(buffer.sink)
        sink(ConsoleLogSink())
        roomLogs(db.logDao(), retention = RoomLogRetention.keepDays(30))
    }

    @Provides
    fun logDao(db: AppDatabase): RoomLogDao = db.logDao()

    @Provides
    @Singleton
    fun taskRepository(logger: ILogger): TaskRepository =
        InMemoryTaskRepository().logged(logger)
}
