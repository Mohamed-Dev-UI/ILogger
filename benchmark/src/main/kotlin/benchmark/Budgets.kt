package benchmark

/**
 * Per-event budgets enforced by `BudgetGateTest` on CI (Linux).
 *
 * A budget is a *ceiling*, not a target: exceeding it by more than
 * [REGRESSION_TOLERANCE] in every measurement of a path (up to three) fails the
 * gate. Change a constant only together with a documented reason — the gate
 * exists to make accidental slowdowns visible.
 */
object Budgets {

    /** Disabled logger: one volatile read on the producer path. */
    const val DISABLED_NANOS: Double = 2.0

    /**
     * `recordNow()` admission into an asynchronous per-sink pipeline.
     *
     * The producer goal is 1 µs/op and the reference host measures ~950 ns, but
     * the same code measured 950–2340 ns/op across attempts on a loaded machine,
     * so this ceiling sits 50% above the goal: a ceiling equal to the goal leaves
     * no room for runner noise, while the regression class this gate has actually
     * caught (a shared `SecureRandom` at 4.2 µs) still clears it by over 2x.
     */
    const val ASYNC_ENQUEUE_NANOS: Double = 1_500.0

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
