package io.ilogger.room

import io.ilogger.core.LogClock
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.SystemClock
import io.ilogger.core.sink.EventCodec
import io.ilogger.serialization.JsonEventCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

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
 * Bind before `flush()`/`close()`. By default an unbound target waits
 * indefinitely (bounded by the logger's `closeTimeoutMillis`); set
 * [bindTimeoutMillis] to fail queued writes with
 * [RoomLogTargetUnboundException] sooner, and note that [close] throws the
 * same exception when the DAO never arrived instead of losing events silently.
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
 * never fail the write. For retention while the app is idle (no writes), run
 * [RoomLogRetentionScheduler].
 *
 * Pass [holds] to make write-triggered purges honor [RoomLogLegalHoldRegistry]:
 * the purge then resolves each expired row and skips held ones instead of
 * calling [RoomLogDao.purgeBefore]. For per-category/entity policies and a
 * pre-purge archive hook, use [RoomLogRetentionEnforcer] with
 * [RoomLogRetentionScheduler] and leave [retention] unset.
 */
public class RoomLogTarget(
    private val codec: EventCodec = JsonEventCodec,
    private val retention: RoomLogRetention? = null,
    private val clock: LogClock = SystemClock,
    private val onError: ((Throwable) -> Unit)? = null,
    private val bindTimeoutMillis: Long = 0,
    private val payloadCipher: RoomLogPayloadCipher? = null,
    private val holds: RoomLogLegalHoldRegistry? = null,
) : LogSink {

    init {
        require(bindTimeoutMillis >= 0) {
            "bindTimeoutMillis must be >= 0 (0 waits indefinitely), got $bindTimeoutMillis"
        }
    }

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

        val dao = awaitDao()
        dao.insertAll(events.map { event -> event.toRoomLogEntity(codec, payloadCipher) })

        val policy = retention ?: return
        val now = clock.nowMillis()
        val last = lastSweepMillis
        if (last != null && now - last < policy.sweepEveryMillis) return

        try {
            val holdRegistry = holds
            if (holdRegistry == null) {
                dao.purgeBefore(now - policy.keepMillis)
            } else {
                RoomLogRetentionEnforcer(
                    dao = dao,
                    policy = RoomLogRetentionPolicy(defaultKeepMillis = policy.keepMillis),
                    holds = holdRegistry,
                    clock = clock,
                ).sweep()
            }
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

    /**
     * Fails loudly when the target is closed before [bind].
     *
     * The engine turns this into a reported sink failure during
     * `logger.close()` instead of pretending the queued events were stored.
     * Once bound, close is a no-op.
     */
    override fun close() {
        if (!boundDao.isCompleted) {
            throw RoomLogTargetUnboundException(
                "RoomLogTarget was closed before bind(dao); queued events were not persisted. " +
                    "Call target.bind(db.logDao()) right after Room.databaseBuilder(...).build().",
            )
        }
    }

    private suspend fun awaitDao(): RoomLogDao {
        if (bindTimeoutMillis <= 0) return boundDao.await()

        val dao = withTimeoutOrNull(bindTimeoutMillis) { boundDao.await() }
        if (dao != null) return dao

        // A parent cancellation also makes withTimeoutOrNull return null;
        // preserve cancellation instead of masking it with our own exception.
        currentCoroutineContext().ensureActive()
        throw RoomLogTargetUnboundException(
            "RoomLogTarget was not bound within ${bindTimeoutMillis}ms; " +
                "call bind(dao) right after Room.databaseBuilder(...).build().",
        )
    }
}
