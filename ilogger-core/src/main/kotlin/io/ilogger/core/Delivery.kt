package io.ilogger.core

/**
 * Why an event (or a single delivery of it) was lost by the engine.
 *
 * Every loss path reports exactly one reason through `onDrop` and increments
 * the matching counter in [DeliveryStats.droppedByReason].
 */
public enum class DropReason {
    /** The oldest queued event was evicted to make room for a newer one. */
    OVERFLOW_OLDEST,

    /** The incoming event was rejected because the per-sink queue was full. */
    OVERFLOW_NEWEST,

    /** The logger had already been closed when the event was produced. */
    CLOSED,

    /** A retrying sink exhausted its attempts. */
    RETRY_EXHAUSTED,

    /** A sink failed under the `DROP` error mode (or a diagnostic fast path). */
    SINK_FAILURE,

    /** The sampler declined the event. */
    SAMPLED,

    /** The ambient [LogContext.suppressSources] suppressed the event. */
    SUPPRESSED,

    /** Reserved for strict-mode validation rejects (see roadmap G-11). */
    STRICT_REJECT,

    /** No binding matched the event's category, so no sink could receive it. */
    MISSING_BINDING,
}

/**
 * One lost event and the reason it was lost.
 *
 * [event] is `null` when the loss happened before an event could be allocated
 * (sampling, source suppression, and events recorded after close).
 */
public data class DroppedEvent(
    public val event: LogEvent?,
    public val reason: DropReason,
    public val error: Throwable? = null,
)

/**
 * Optional receiver for events the engine could not deliver.
 *
 * Dead-letter writes are performed on a dedicated background pipeline, so a
 * slow dead-letter destination never blocks producers or the regular sinks.
 * Implementations that must survive a process restart should persist the
 * dropped event (file, Room, remote queue).
 */
public fun interface DeadLetterSink : AutoCloseable {

    /** Receives one dropped event. */
    public suspend fun write(dropped: DroppedEvent)

    /** Flushes internal buffers. Called on logger flush/close. */
    public suspend fun flush(): Unit {}

    /** Releases resources. Called once on logger close. */
    override fun close(): Unit {}

    public companion object {
        /** Adapts an ordinary [LogSink]; drops without an event are ignored. */
        public fun of(sink: LogSink): DeadLetterSink = object : DeadLetterSink {
            override suspend fun write(dropped: DroppedEvent) {
                dropped.event?.let { sink.write(it) }
            }

            override suspend fun flush() {
                sink.flush()
            }

            override fun close() {
                sink.close()
            }
        }
    }
}

/**
 * Queue state and counters of one sink binding.
 *
 * [accepted] and [delivered] describe events that went through *this binding's
 * pipeline*; direct inline diagnostics bypass pipelines and appear only in the
 * logger-level [DeliveryStats] totals. [pending] is the current queue depth —
 * the per-binding value producers need for shedding decisions.
 */
public data class BindingStats(
    public val index: Int,
    public val category: String?,
    public val sink: String,
    public val pending: Int,
    public val inFlight: Int,
    public val accepted: Long,
    public val delivered: Long,
)

/**
 * Delivery counters of one logger.
 *
 * A logger with several bindings counts one *admission* and one *delivery* per
 * binding and event: an event routed to two sinks contributes two of each.
 * [pending] counts queued events that no worker has picked up yet; [inFlight]
 * counts events currently being written to a sink. [bindings] exposes the same
 * state per sink.
 */
public data class DeliveryStats(
    public val accepted: Long,
    public val delivered: Long,
    public val pending: Int,
    public val inFlight: Int,
    public val droppedByReason: Map<DropReason, Long>,
    public val bindings: List<BindingStats> = emptyList(),
    /**
     * Events delivered after [EventLimits] rewrote their payload
     * ([LimitAction.TRUNCATE]). Zero when limits are disabled.
     */
    public val truncated: Long = 0,
) {
    /** Total number of lost deliveries across every reason. */
    public val dropped: Long
        get() = droppedByReason.values.sum()

    /** Alias for [pending]: events waiting in per-sink queues. */
    public val queueDepth: Int
        get() = pending

    public companion object {
        public val EMPTY: DeliveryStats = DeliveryStats(
            accepted = 0,
            delivered = 0,
            pending = 0,
            inFlight = 0,
            droppedByReason = emptyMap(),
        )
    }
}

/**
 * Outcome of [ILogger.flush]. [timedOut] is `true` when the timeout elapsed
 * before every event enqueued before the call was processed and every sink was
 * flushed; counters still describe the state at return time.
 */
public data class FlushReport(
    public val timedOut: Boolean,
    public val accepted: Long,
    public val delivered: Long,
    public val pending: Int,
    public val inFlight: Int,
    public val droppedByReason: Map<DropReason, Long>,
)

/** A sink that failed to close; the remaining sinks were still closed. */
public data class SinkFailure(
    public val sink: String,
    public val error: Throwable,
)

/**
 * Outcome of [ILogger.closeAndReport]. A graceful close has `timedOut == false`
 * and `pending == 0`; [droppedByReason] includes events lost during shutdown.
 */
public data class CloseReport(
    public val timedOut: Boolean,
    public val accepted: Long,
    public val delivered: Long,
    public val pending: Int,
    public val inFlight: Int,
    public val droppedByReason: Map<DropReason, Long>,
    public val sinkFailures: List<SinkFailure>,
)
