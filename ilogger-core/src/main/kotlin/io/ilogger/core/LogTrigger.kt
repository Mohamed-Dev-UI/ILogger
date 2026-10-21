package io.ilogger.core

import kotlinx.coroutines.CancellationException

/**
 * A [LogSink] that forwards finalized events to an observer ("trigger").
 *
 * Unlike an ordinary sink, a trigger is a **side channel**: its failures are
 * reported to [onError] and never rethrown, so the engine's retry/propagate
 * policy does not apply to it and a failed trigger can never fail the
 * instrumented operation. That makes it safe to persist, mirror or alert from
 * a trigger without risking the producer.
 *
 * Batching follows the engine: with `DispatchMode.BUFFERED` [writeAll]
 * receives a real batch, so a single database insert can cover it.
 *
 * @param onEvents receives each delivered batch; never called with an empty
 *   list.
 * @param onError invoked with the first event of the failed batch and the
 *   throwable. `null` silently ignores trigger failures; a throwing handler is
 *   also contained so it cannot reach the producer.
 */
public class TriggerSink(
    private val onEvents: suspend (List<LogEvent>) -> Unit,
    private val onError: ((LogEvent?, Throwable) -> Unit)? = null,
) : LogSink {

    override suspend fun write(event: LogEvent): Unit = writeAll(listOf(event))

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        try {
            onEvents(events)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // A failing observer must never escape into the producer, including
            // when the application's own error callback is broken.
            try {
                onError?.invoke(events.firstOrNull(), error)
            } catch (handlerFailure: Throwable) {
                if (handlerFailure is CancellationException) throw handlerFailure
            }
        }
    }
}

/**
 * Registers a per-event trigger: [block] runs for every event that reaches
 * this binding, with no risk of failing the producer and no engine retries.
 *
 * Prefer [onEvents] when the work can take a batch (database inserts, network
 * batches); this overload exists for observers that are inherently per event.
 *
 * @param category only receives events with this `LogEvent.category`; `null`
 *   receives every category.
 * @param onError receives trigger failures; `null` drops them silently.
 */
public fun ILoggerBuilder.onEvent(
    category: String? = null,
    onError: ((LogEvent?, Throwable) -> Unit)? = null,
    block: suspend (LogEvent) -> Unit,
): Unit = sink(
    TriggerSink(
        onEvents = { events -> events.forEach { block(it) } },
        onError = onError,
    ),
    category,
)

/**
 * Registers a batch trigger: [block] receives each batch the engine delivers
 * (the whole batch under `DispatchMode.BUFFERED`). Trigger failures are
 * isolated exactly like [onEvent]'s.
 *
 * ```kotlin
 * ILogger.create {
 *     onEvents { events -> myLogDao.insertAll(events.map(MyLogRow::from)) }
 * }
 * ```
 */
public fun ILoggerBuilder.onEvents(
    category: String? = null,
    onError: ((LogEvent?, Throwable) -> Unit)? = null,
    block: suspend (List<LogEvent>) -> Unit,
): Unit = sink(TriggerSink(onEvents = block, onError = onError), category)
