package io.ilogger.room

/**
 * Retention policy for [RoomLogEntity]: delete rows older than [keepMillis],
 * at most once per [sweepEveryMillis] of log writes.
 *
 * Sweeping happens inside the (already off-hot-path) dispatch worker, right
 * after a successful insert, so no extra coroutine or timer is required for a
 * busy process. For an idle app, drive the same policy with
 * [RoomLogRetentionScheduler]. Retention is opt-in: [RoomLogTarget] purges
 * nothing unless a policy is supplied.
 */
public class RoomLogRetention(
    /** Maximum age of a retained row, in milliseconds. */
    public val keepMillis: Long,
    /** Minimum wall-clock gap between two purge sweeps. */
    public val sweepEveryMillis: Long,
) {
    init {
        require(keepMillis >= 0) { "keepMillis must be >= 0, got $keepMillis" }
        require(sweepEveryMillis > 0) { "sweepEveryMillis must be > 0, got $sweepEveryMillis" }
    }

    public companion object {
        /**
         * Keeps [days] days of events, sweeping at most every
         * [sweepEveryHours] hours.
         */
        public fun keepDays(days: Int, sweepEveryHours: Int = 24): RoomLogRetention {
            require(days > 0) { "days must be > 0, got $days" }
            require(sweepEveryHours > 0) { "sweepEveryHours must be > 0, got $sweepEveryHours" }
            return RoomLogRetention(
                keepMillis = days * 86_400_000L,
                sweepEveryMillis = sweepEveryHours * 3_600_000L,
            )
        }
    }
}
