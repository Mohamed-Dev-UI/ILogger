package io.ilogger.room

import io.ilogger.core.LogClock
import io.ilogger.core.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs retention on a timer, independent of writes.
 *
 * [RoomLogTarget] sweeps after inserts, which is enough for busy processes but
 * never runs when the app is idle. Start this scheduler from an
 * application-scoped coroutine to purge expired rows while nothing is being
 * logged:
 *
 * ```kotlin
 * val retention = RoomLogRetention.keepDays(30)
 * RoomLogRetentionScheduler(db.logDao(), retention).start(appScope)
 * ```
 *
 * Pass a [RoomLogRetentionEnforcer] instead of a single [RoomLogRetention] to
 * honor per-category/entity policies, legal holds and a pre-purge archive hook:
 *
 * ```kotlin
 * RoomLogRetentionScheduler(enforcer, sweepEveryMillis = 3_600_000).start(appScope)
 * ```
 *
 * The first sweep runs immediately, then one sweep per interval. A delayed
 * coroutine that resumes late (the device was asleep) sweeps as soon as it
 * runs again, so missed windows are caught up rather than skipped. Errors go
 * to [onError] and never cancel the loop; cancellation of [start]'s scope
 * stops it normally.
 *
 * Running the scheduler alongside a [RoomLogTarget] with the same policy is
 * harmless: purges delete by cutoff and a second sweep in the same window
 * simply reports zero removed rows.
 */
public class RoomLogRetentionScheduler private constructor(
    private val sweepOnce: suspend () -> Int,
    private val intervalMillis: Long,
    private val onError: ((Throwable) -> Unit)?,
) {

    /** Scheduler for the single-window [retention] policy. */
    public constructor(
        dao: RoomLogDao,
        retention: RoomLogRetention,
        clock: LogClock = SystemClock,
        onError: ((Throwable) -> Unit)? = null,
    ) : this(
        sweepOnce = { dao.purgeBefore(clock.nowMillis() - retention.keepMillis) },
        intervalMillis = retention.sweepEveryMillis,
        onError = onError,
    )

    /** Scheduler for a hold-aware [RoomLogRetentionEnforcer]. */
    public constructor(
        enforcer: RoomLogRetentionEnforcer,
        sweepEveryMillis: Long,
        onError: ((Throwable) -> Unit)? = null,
    ) : this(
        sweepOnce = { enforcer.sweep().removed },
        intervalMillis = sweepEveryMillis,
        onError = onError,
    )

    init {
        require(intervalMillis > 0) { "sweepEveryMillis must be > 0, got $intervalMillis" }
    }

    /** Runs one sweep now and returns the number of removed rows. */
    public suspend fun sweep(): Int = sweepOnce()

    /**
     * Sweeps immediately and then every interval until the returned job (or
     * [scope]) is cancelled.
     */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                sweepOnce()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                try {
                    onError?.invoke(error)
                } catch (handlerFailure: Throwable) {
                    if (handlerFailure is CancellationException) throw handlerFailure
                }
            }
            delay(intervalMillis)
        }
    }
}
