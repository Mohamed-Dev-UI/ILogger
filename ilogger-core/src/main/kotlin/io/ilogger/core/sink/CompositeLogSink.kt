package io.ilogger.core.sink

import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink

/**
 * Fans events out to several sinks while keeping the event immutable.
 *
 * Batches are forwarded whole: one [writeAll] call per inner sink per engine
 * batch, so batch-oriented destinations (databases, files, HTTP) keep their
 * batching behavior instead of degrading to per-event writes.
 *
 * ## Error modes
 *
 * - [ErrorMode.BEST_EFFORT] (default): a failing sink never blocks the others
 *   and never propagates; failures are reported through [onError]. The batch
 *   is considered delivered by the composite once written to every non-failing
 *   sink, so upstream retry policies do not duplicate writes to sinks that
 *   already succeeded.
 * - [ErrorMode.FAIL_FAST]: the first failure propagates immediately (skipping
 *   remaining sinks). Combined with upstream `RETRY` this can duplicate events
 *   in sinks that already received them — use with subsequent-id
 *   deduplication in mind.
 *
 * [DirectLogSink] is implemented only when every inner sink supports it; the
 * engine uses it for INLINE diagnostics without coroutine machinery.
 */
public class CompositeLogSink(
    private val sinks: List<LogSink>,
    private val errorMode: ErrorMode = ErrorMode.BEST_EFFORT,
    private val onError: ((event: LogEvent, sink: LogSink, error: Throwable) -> Unit)? = null,
) : DirectLogSink {

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
                report(event, sink, error)
            }
        }
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        for (sink in sinks) {
            try {
                sink.writeAll(events)
            } catch (error: Throwable) {
                if (errorMode == ErrorMode.FAIL_FAST) throw error
                report(events.first(), sink, error)
            }
        }
    }

    override fun writeDirect(event: LogEvent) {
        check(sinks.all { it is DirectLogSink }) {
            "CompositeLogSink.writeDirect requires every inner sink to implement DirectLogSink"
        }
        for (sink in sinks) {
            try {
                (sink as DirectLogSink).writeDirect(event)
            } catch (error: Throwable) {
                if (errorMode == ErrorMode.FAIL_FAST) throw error
                report(event, sink, error)
            }
        }
    }

    private fun report(event: LogEvent, sink: LogSink, error: Throwable) {
        try {
            onError?.invoke(event, sink, error)
        } catch (_: Throwable) {
            // A broken error handler must not stop delivery to remaining sinks.
        }
    }

    public enum class ErrorMode { BEST_EFFORT, FAIL_FAST }
}
