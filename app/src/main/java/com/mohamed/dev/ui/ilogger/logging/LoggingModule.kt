package com.mohamed.dev.ui.ilogger.logging

import android.content.Context
import androidx.room.Room
import com.mohamed.dev.ui.ilogger.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ilogger.android.AndroidFileSink
import io.ilogger.android.AndroidLifecycleHooks
import io.ilogger.android.CrashBuffer
import io.ilogger.android.CrashHandoff
import io.ilogger.android.LogcatSink
import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.ErrorMode
import io.ilogger.core.ILogger
import io.ilogger.core.OverflowPolicy
import io.ilogger.core.StrictMode
import io.ilogger.core.onEvents
import io.ilogger.core.sink.TextFileSink
import io.ilogger.room.RoomLogDao
import io.ilogger.room.RoomLogRetention
import io.ilogger.room.RoomLogTarget
import io.ilogger.room.withAuditCallback
import io.ilogger.serialization.JsonEventCodec
import java.io.File
import javax.inject.Singleton

/**
 * Production-shaped wiring, owned by the application:
 *
 * ```
 * RoomLogTarget -> ILogger -> sinks (feed, Room, Logcat, JSONL)
 * AppDatabase.logDao -> target.bind -> TaskDao -> TaskRepository -> SessionCache
 * ```
 *
 * - the logger exists **before** the database so `withAuditCallback` can record
 *   `room.database.created/opened`; [RoomLogTarget.bind] drains those queued
 *   lifecycle events once the DAO exists;
 * - the live UI feed is a catch-all sink;
 * - Room is the durable queryable trail (30-day retention sweep);
 * - the JSONL file only receives `category = "audit"` events;
 * - Logcat only receives `category = "diagnostics"` events;
 * - an `onEvents` trigger feeds dashboard metrics with isolated failures.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    const val DATABASE_NAME = "ilogger-demo.db"
    const val AUDIT_CATEGORY = "audit"
    const val DIAGNOSTIC_CATEGORY = "diagnostics"

    @Provides
    @Singleton
    fun auditTarget(feed: EventBuffer): RoomLogTarget = RoomLogTarget(
        retention = RoomLogRetention.keepDays(days = 30),
        onError = { error -> feed.reportRetentionError(error) },
    )

    @Provides
    @Singleton
    fun logger(
        feed: EventBuffer,
        metrics: AuditMetrics,
        target: RoomLogTarget,
        files: AuditFileStore,
        crashBuffer: CrashBuffer,
    ): ILogger = ILogger.create {
        dispatch = DispatchMode.BUFFERED
        bufferSize = 512
        batchSize = 32
        flushIntervalMillis = 150
        closeTimeoutMillis = 3_000
        overflow = OverflowPolicy.DROP_OLDEST
        onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)

        // Debug builds reject non-log-safe payloads and report them through
        // onDrop; release pays nothing for validation.
        strictMode = if (BuildConfig.DEBUG) StrictMode.WARN else StrictMode.OFF

        // Catch-all: the in-app live view sees every category.
        sink(feed.sink)

        // Catch-all: durable, queryable history (library-owned ilogger_events table).
        sink(target)

        // Catch-all ring for the crash handoff; only the last 128 events are kept.
        sink(crashBuffer)

        // Android's console bridge: diagnostic-shaped events only.
        sink(LogcatSink(tag = "ILogger"), category = DIAGNOSTIC_CATEGORY)

        // Append-only JSONL forensics: audit-category events only, rotated on size.
        // AndroidFileSink never touches the main thread, even under INLINE dispatch.
        sink(
            AndroidFileSink.create(
                file = files.file,
                codec = JsonEventCodec,
                rotation = TextFileSink.Rotation(maxBytesPerFile = 12 * 1024, maxFiles = 3),
            ),
            category = AUDIT_CATEGORY,
        )

        // Side-channel dashboard aggregation; failures never reach the producer.
        onEvents { events -> metrics.record(events) }

        onDispatchError = { event, sink, error -> feed.reportDispatchError(event, sink, error) }
    }

    @Provides
    @Singleton
    fun crashBuffer(): CrashBuffer = CrashBuffer(capacity = 128)

    /** Process-wide crash handoff; recent events are persisted before the process dies. */
    @Provides
    @Singleton
    fun crashHandoff(
        @ApplicationContext context: Context,
        buffer: CrashBuffer,
    ): CrashHandoff = CrashHandoff(
        buffer = buffer,
        directory = File(context.filesDir, "ilogger/crash"),
    ).also { handoff -> handoff.install() }

    /** Flushes buffered events when Android backgrounds the process. */
    @Provides
    @Singleton
    fun lifecycleHooks(logger: ILogger): AndroidLifecycleHooks =
        AndroidLifecycleHooks(logger).attach()

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
        logger: ILogger,
        target: RoomLogTarget,
    ): AppDatabase =
        Room.databaseBuilder<AppDatabase>(context, DATABASE_NAME)
            // Demo app: the task table was added after the log-only schema, and a
            // destructive fallback keeps existing debug installs usable.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .withAuditCallback(logger, label = DATABASE_NAME)
            .build()
            .also { database -> target.bind(database.logDao()) }

    @Provides
    @Singleton
    fun taskDao(db: AppDatabase, logger: ILogger): TaskDao = db.logged(logger).taskDao()

    @Provides
    fun logDao(db: AppDatabase): RoomLogDao = db.logDao()

    @Provides
    @Singleton
    fun taskRepository(impl: RoomTaskRepository, logger: ILogger): TaskRepository = impl.logged(logger)

    @Provides
    @Singleton
    fun sessionCache(impl: InMemorySessionCache, logger: ILogger): SessionCache =
        impl.logged(logger)
}
