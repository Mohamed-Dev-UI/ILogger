package io.ilogger.annotations

/**
 * How generated instrumentation treats a `CancellationException` thrown by an
 * instrumented suspend function.
 *
 * Cancellation is control flow, not a business failure: by default it is
 * rethrown untouched and no FAILURE event is emitted, so cancelled coroutines
 * do not pollute failure dashboards.
 */
public enum class OnCancellation {
    /** Rethrow the cancellation without recording an event (default). */
    SKIP,

    /**
     * Rethrow the cancellation and emit a FAILURE event marked with
     * [io.ilogger.core.LogFailure.cancelled]. The event is dispatched
     * non-suspendingly, so logging can never replace the cancellation.
     */
    LOG,
}
