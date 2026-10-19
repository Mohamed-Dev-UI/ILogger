package io.ilogger.annotations

/**
 * How the runtime hands an event to its sinks.
 *
 * [DEFAULT] inherits the application-level dispatch configuration.
 * [INLINE] writes the event synchronously on the caller's coroutine (strongest
 * ordering and failure visibility, highest caller latency).
 * [ASYNC] hands the event to the engine worker; the caller does not wait for
 * the sink to finish.
 * [BUFFERED] additionally batches events for sinks that benefit from batches
 * (databases, network).
 */
public enum class DispatchMode {
    /** Use the mode configured at the application level. */
    DEFAULT,

    /** Write synchronously, await sink completion. */
    INLINE,

    /** Dispatch asynchronously to the engine worker; single-event writes. */
    ASYNC,

    /** Dispatch asynchronously and batch events before writing. */
    BUFFERED,
}
