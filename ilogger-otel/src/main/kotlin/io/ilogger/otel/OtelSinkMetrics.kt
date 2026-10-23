package io.ilogger.otel

import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Decorates a [LogSink] with latency, volume, error and retry metrics.
 *
 * ```kotlin
 * val sink = OtelSinkMetrics.wrap(FileLogSink(file), meter)
 * val logger = ILogger.create { sink(sink) }
 * ```
 *
 * ## Instruments
 *
 * | Name (default prefix) | Type | Labels |
 * |---|---|---|
 * | `ilogger.sink.write.duration` | histogram (ms) | `sink`, `outcome` (success/error/cancelled) |
 * | `ilogger.sink.events` | counter | `sink` — events written successfully |
 * | `ilogger.sink.errors` | counter | `sink`, `error.type` — failed write attempts |
 * | `ilogger.sink.retries` | counter | `sink` — events re-attempted after a failure |
 *
 * Retries are observed, not injected: the engine's `ErrorMode.RETRY` calls the
 * sink again with the same event ids, and this decorator counts every event
 * whose previous attempt failed (a bounded, 128-entry id memory is kept). The
 * counter therefore works for any sink without core having to expose engine
 * internals.
 *
 * ## Fast path
 *
 * Use [wrap] instead of the constructor to preserve the synchronous
 * [DirectLogSink] contract: wrapping a direct sink with `wrap` still delivers
 * inline diagnostics without coroutine machinery. The constructor form accepts
 * any [LogSink] and only implements the async interface.
 *
 * The decorator rethrows exactly what the sink threw: it observes, never
 * swallows, so error modes and retries behave as configured.
 *
 * @param delegate sink receiving the events
 * @param meter meter from the application's `MeterProvider`
 * @param sinkName value of the `sink` label; defaults to the class name
 * @param namePrefix prefix for every instrument name
 */
public class OtelSinkMetrics(
    private val delegate: LogSink,
    meter: Meter,
    sinkName: String = defaultSinkName(delegate),
    namePrefix: String = "ilogger.",
) : LogSink {

    private val recorder = SinkMetricsRecorder(meter, sinkName, namePrefix)

    override suspend fun write(event: LogEvent): Unit = attempt(listOf(event)) {
        delegate.write(event)
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        attempt(events) { delegate.writeAll(events) }
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }

    private suspend fun attempt(events: List<LogEvent>, block: suspend () -> Unit) {
        val startedAt = recorder.started(events)
        try {
            block()
            recorder.success(events, startedAt)
        } catch (cancellation: CancellationException) {
            recorder.cancelled(startedAt)
            throw cancellation
        } catch (error: Throwable) {
            recorder.failure(events, startedAt, error)
            throw error
        }
    }

    public companion object {

        internal fun defaultSinkName(sink: LogSink): String =
            sink.javaClass.simpleName.ifBlank { "sink" }

        /**
         * Wraps [delegate] while preserving its [DirectLogSink] fast path when
         * it has one.
         */
        public fun wrap(
            delegate: LogSink,
            meter: Meter,
            sinkName: String = defaultSinkName(delegate),
            namePrefix: String = "ilogger.",
        ): LogSink = if (delegate is DirectLogSink) {
            DirectSinkMetrics(delegate, meter, sinkName, namePrefix)
        } else {
            OtelSinkMetrics(delegate, meter, sinkName, namePrefix)
        }
    }
}

/** Direct-sink variant: inline diagnostics keep their synchronous contract. */
internal class DirectSinkMetrics(
    private val delegate: DirectLogSink,
    meter: Meter,
    sinkName: String,
    namePrefix: String,
) : DirectLogSink {

    private val recorder = SinkMetricsRecorder(meter, sinkName, namePrefix)

    override suspend fun write(event: LogEvent) {
        writeDirect(event)
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        val startedAt = recorder.started(events)
        try {
            delegate.writeAll(events)
            recorder.success(events, startedAt)
        } catch (cancellation: CancellationException) {
            recorder.cancelled(startedAt)
            throw cancellation
        } catch (error: Throwable) {
            recorder.failure(events, startedAt, error)
            throw error
        }
    }

    override fun writeDirect(event: LogEvent) {
        val startedAt = recorder.started(listOf(event))
        try {
            delegate.writeDirect(event)
            recorder.success(listOf(event), startedAt)
        } catch (cancellation: CancellationException) {
            recorder.cancelled(startedAt)
            throw cancellation
        } catch (error: Throwable) {
            recorder.failure(listOf(event), startedAt, error)
            throw error
        }
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}

/**
 * Instrument handles plus the bounded failed-id memory shared by both
 * decorator shapes.
 */
internal class SinkMetricsRecorder(
    meter: Meter,
    private val sinkName: String,
    namePrefix: String,
) {

    init {
        require(sinkName.isNotBlank()) { "sinkName must not be blank" }
        require(namePrefix.isNotEmpty()) { "namePrefix must not be empty" }
    }

    private val sinkKey: AttributeKey<String> = AttributeKey.stringKey("sink")
    private val outcomeKey: AttributeKey<String> = AttributeKey.stringKey("outcome")
    private val errorTypeKey: AttributeKey<String> = AttributeKey.stringKey("error.type")

    private val baseAttributes: Attributes = Attributes.of(sinkKey, sinkName)
    private val successAttributes: Attributes = Attributes.of(sinkKey, sinkName, outcomeKey, "success")
    private val errorAttributes: Attributes = Attributes.of(sinkKey, sinkName, outcomeKey, "error")
    private val cancelledAttributes: Attributes = Attributes.of(sinkKey, sinkName, outcomeKey, "cancelled")

    private val duration: DoubleHistogram = meter.histogramBuilder("${namePrefix}sink.write.duration")
        .setDescription("Time spent inside one sink write, including batches")
        .setUnit("ms")
        .build()
    private val events: LongCounter = meter.counterBuilder("${namePrefix}sink.events")
        .setDescription("Events written successfully by the sink")
        .build()
    private val errors: LongCounter = meter.counterBuilder("${namePrefix}sink.errors")
        .setDescription("Failed sink write attempts")
        .build()
    private val retries: LongCounter = meter.counterBuilder("${namePrefix}sink.retries")
        .setDescription("Events re-attempted after a failed sink write")
        .build()

    private val lock = Any()
    private val failedIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_REMEMBERED_FAILURES
    }

    /** Records retries for this attempt and returns the start timestamp. */
    fun started(events: List<LogEvent>): Long {
        val retried = synchronized(lock) { events.count { it.id in failedIds } }
        if (retried > 0) retries.add(retried.toLong(), baseAttributes)
        return System.nanoTime()
    }

    fun success(events: List<LogEvent>, startedAtNanos: Long) {
        duration.record(elapsedMillis(startedAtNanos), successAttributes)
        this.events.add(events.size.toLong(), baseAttributes)
        if (events.isNotEmpty()) {
            synchronized(lock) { events.forEach { failedIds.remove(it.id) } }
        }
    }

    fun failure(events: List<LogEvent>, startedAtNanos: Long, error: Throwable) {
        duration.record(elapsedMillis(startedAtNanos), errorAttributes)
        errors.add(
            1,
            Attributes.of(sinkKey, sinkName, errorTypeKey, error.javaClass.name),
        )
        if (events.isNotEmpty()) {
            synchronized(lock) { events.forEach { failedIds[it.id] = true } }
        }
    }

    fun cancelled(startedAtNanos: Long) {
        duration.record(elapsedMillis(startedAtNanos), cancelledAttributes)
    }

    private fun elapsedMillis(startedAtNanos: Long): Double =
        TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAtNanos) / 1_000.0

    private companion object {
        const val MAX_REMEMBERED_FAILURES: Int = 128
    }
}
