package io.ilogger.room

import io.ilogger.core.LogClock
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.SystemClock
import io.ilogger.core.sink.EventCodec
import io.ilogger.serialization.JsonEventCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * A [LogSink] that persists every event into [RoomLogEntity] through
 * [RoomLogDao].
 *
 * ## Late binding
 *
 * The DAO is optional at construction and supplied with [bind]. This resolves
 * the common Android ordering problem: the logger must exist before
 * `Room.databaseBuilder(...).build()` so `.withAuditCallback(logger)` can see
 * lifecycle events, but the DAO only exists after `build()`. Events queued
 * before [bind] wait in the dispatch worker and are written when the DAO
 * arrives — they are never dropped.
 *
 * Bind before `flush()`/`close()`: otherwise the worker waits for the DAO
 * until the logger's `closeTimeoutMillis` expires.
 *
 * ## Writes
 *
 * The engine calls [writeAll] with a real batch under
 * `DispatchMode.BUFFERED`; one [RoomLogDao.insertAll] call covers it. Insert
 * failures propagate to the engine's `ErrorMode` (audit events default to
 * `RETRY`), and `REPLACE` on the event-id primary key makes redelivery
 * idempotent.
 *
 * ## Retention
 *
 * When a [RoomLogRetention] policy is supplied, a successful insert is
 * followed by at most one [RoomLogDao.purgeBefore] per policy interval. Purge
 * failures are reported to [onError] (a throwing handler is contained) and
 * never fail the write.
 */
public class RoomLogTarget(
    private val codec: EventCodec = JsonEventCodec,
    private val retention: RoomLogRetention? = null,
    private val clock: LogClock = SystemClock,
    private val onError: ((Throwable) -> Unit)? = null,
) : LogSink {

    private val boundDao = CompletableDeferred<RoomLogDao>()

    @Volatile
    private var lastSweepMillis: Long? = null

    /** Supplies the DAO; safe to call once, later calls are ignored. */
    public fun bind(dao: RoomLogDao) {
        boundDao.complete(dao)
    }

    override suspend fun write(event: LogEvent): Unit = writeAll(listOf(event))

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return

        val dao = boundDao.await()
        dao.insertAll(events.map { event -> event.toRoomLogEntity(codec) })

        val policy = retention ?: return
        val now = clock.nowMillis()
        val last = lastSweepMillis
        if (last != null && now - last < policy.sweepEveryMillis) return

        try {
            dao.purgeBefore(now - policy.keepMillis)
            lastSweepMillis = now
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Retention is best-effort: neither the purge nor a broken error
            // handler may fail the insert that already succeeded.
            try {
                onError?.invoke(error)
            } catch (handlerFailure: Throwable) {
                if (handlerFailure is CancellationException) throw handlerFailure
            }
        }
    }
}
