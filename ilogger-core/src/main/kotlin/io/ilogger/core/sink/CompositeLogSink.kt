package io.ilogger.core.sink

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink

/**
 * Fans events out to several sinks while keeping the event immutable.
 *
 * ## Error modes
 *
 * - [ErrorMode.BEST_EFFORT] (default): a failing sink never blocks the others
 *   and never propagates; failures are reported through [onError]. The event
 *   is considered delivered by the composite once written to every
 *   non-failing sink, so upstream retry policies do not duplicate writes to
 *   sinks that already succeeded.
 * - [ErrorMode.FAIL_FAST]: the first failure propagates immediately (skipping
 *   remaining sinks). Combined with upstream `RETRY` this can duplicate events
 *   in sinks that already received them — use with subsequent-id
 *   deduplication in mind.
 */
public class CompositeLogSink(
    private val sinks: List<LogSink>,
    private val errorMode: ErrorMode = ErrorMode.BEST_EFFORT,
    private val onError: ((event: LogEvent, sink: LogSink, error: Throwable) -> Unit)? = null,
) : LogSink {

    init {
        require(sinks.isNotEmpty()) { "CompositeLogSink requires at least one sink" }
    }

    public constructor(vararg sinks: LogSink) : this(sinks.toList())

    override suspend fun write(event: LogEvent) {
        for (sink in sinks) {
            try {
                sink.write(event)
            } catch (error: Throwable) {
                if (errorMode == ErrorMode.FAIL_FAST) throw error
                // BEST_EFFORT: report and keep delivering to the other sinks.
                onError?.invoke(event, sink, error)
            }
        }
    }

    public enum class ErrorMode { BEST_EFFORT, FAIL_FAST }
}
