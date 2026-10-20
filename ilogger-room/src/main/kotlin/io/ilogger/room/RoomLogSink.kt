package io.ilogger.room

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink

/**
 * Stores log events in the application's own Room table through its own DAO.
 *
 * The log entity belongs to the application's database schema (an "external"
 * `LogEntity`, `log_events` table, own columns and indexes) — this sink only
 * bridges [LogEvent] batches into it. Schema ownership stays with the app:
 * future library versions never force a migration on that table.
 *
 * The write target is intentionally a plain suspend lambda instead of a
 * library-owned DAO type, so the sink works with any mapping the app chooses
 * (flat columns, a JSON payload column via `ilogger-serialization`'s
 * [io.ilogger.serialization.JsonEventCodec], or a mixture). Typical wiring:
 *
 * ```kotlin
 * val logger = ILogger.create {
 *     dispatch = DispatchMode.BUFFERED   // DB writes leave the hot path
 *     batchSize = 8
 *     sink(RoomLogSink { events ->
 *         logDao.insertAll(events.map { it.toLogRow() })
 *     })
 * }
 * ```
 *
 * Semantics:
 * - **Batching**: [writeAll] forwards the whole list in a single invocation,
 *   so a batched `@Insert` runs as one transaction per batch. Prefer buffered
 *   dispatch (`DispatchMode.BUFFERED`, `batchSize`) so the engine hands real
 *   batches over; `close()`/`flush()` drain the pending queue.
 * - **Errors**: a throwing lambda propagates to the engine, whose
 *   [io.ilogger.core.ErrorMode] decides drop/retry/propagate. Redelivery is
 *   naturally idempotent when the insert targets the event id (Room
 *   `OnConflictStrategy.REPLACE`).
 * - **Duration**: writes are awaited inside the engine's dispatch path; an
 *   long-blocking lambda delays subsequent batches, not the instrumented call
 *   sites (except under `DispatchMode.INLINE`).
 * - **No Room dependency**: this module keeps Room imports out of the sink so
 *   it stays multiplatform-clean (JVM/Android) — Room only appears in the
 *   consumer's mapping code.
 */
public class RoomLogSink(
    /** Insert bridge: maps and persists one batch into the app's log table. */
    private val insertBatch: suspend (List<LogEvent>) -> Unit,
) : LogSink {

    override suspend fun write(event: LogEvent) {
        insertBatch(listOf(event))
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        insertBatch(events)
    }
}
