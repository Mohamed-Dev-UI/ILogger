package benchmark

/**
 * Per-event budgets enforced by `BudgetGateTest` on CI (Linux).
 *
 * A budget is a *ceiling*, not a target: exceeding it by more than
 * [REGRESSION_TOLERANCE] fails the gate. Change a constant only together with
 * a documented reason — the gate exists to make accidental slowdowns visible.
 */
object Budgets {

    /** Disabled logger: one volatile read on the producer path. */
    const val DISABLED_NANOS: Double = 2.0

    /** `recordNow()` admission into an asynchronous per-sink pipeline. */
    const val ASYNC_ENQUEUE_NANOS: Double = 1_000.0

    /** `recordNow()` admission into a buffered (batched) pipeline. */
    const val BUFFERED_AUDIT_NANOS: Double = 3_000.0

    /** Allowed overshoot before a measurement counts as a regression (15%). */
    const val REGRESSION_TOLERANCE: Double = 0.15

    fun isRegression(measuredNanos: Double, budgetNanos: Double): Boolean =
        measuredNanos > budgetNanos * (1.0 + REGRESSION_TOLERANCE)

    fun describe(label: String, measuredNanos: Double, budgetNanos: Double): String {
        val limit = budgetNanos * (1.0 + REGRESSION_TOLERANCE)
        return "%s: %.2f ns/op exceeds the %.2f ns budget (+%d%% = %.2f ns/op)"
            .format(label, measuredNanos, budgetNanos, (REGRESSION_TOLERANCE * 100).toInt(), limit)
    }
}
