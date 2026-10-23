package io.ilogger.otel

import io.ilogger.core.BindingStats
import io.ilogger.core.DropReason
import io.ilogger.core.ILogger
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableLongMeasurement

/**
 * Publishes ILogger delivery state through an OpenTelemetry [Meter].
 *
 * Metrics are **pull-based**: the instruments are observable gauges/counters
 * whose callbacks read [ILogger.stats] only when a `MeterProvider` collects.
 * There is no polling thread, no work while nothing collects, and every
 * standard OTel registry/exporter (Prometheus, OTLP, logging) sees the same
 * numbers. Micrometer can consume them through its OTel bridge.
 *
 * ## Instruments
 *
 * | Name (default prefix) | Type | Meaning |
 * |---|---|---|
 * | `ilogger.events.accepted` | counter (delta) | admissions since the last collection |
 * | `ilogger.events.delivered` | counter (delta) | deliveries since the last collection |
 * | `ilogger.events.dropped` | counter (delta) | losses, attributed by `reason` |
 * | `ilogger.events.truncated` | counter (delta) | events rewritten by limits |
 * | `ilogger.queue.depth` | gauge | queued events across bindings |
 * | `ilogger.events.in_flight` | gauge | events currently written to sinks |
 * | `ilogger.binding.pending` | gauge | per-binding queue depth (`index`, `sink`, `category`) |
 * | `ilogger.binding.in_flight` | gauge | per-binding in-flight events |
 *
 * The returned object owns the instrument registrations: [close] unregisters
 * them. Closing the logger first is fine; the gauges simply read zero.
 *
 * @param logger the logger whose counters are exported
 * @param meter meter from the application's `MeterProvider`
 * @property namePrefix prefix for every instrument name
 */
public class OtelLoggerMetrics(
    private val logger: ILogger,
    meter: Meter,
    public val namePrefix: String = "ilogger.",
) : AutoCloseable {

    private val lock = Any()
    private var lastAccepted = 0L
    private var lastDelivered = 0L
    private var lastTruncated = 0L
    private var lastDropped: Map<DropReason, Long> = emptyMap()
    private val handles = ArrayList<AutoCloseable>()
    private var closed = false

    init {
        require(namePrefix.isNotEmpty()) { "namePrefix must not be empty" }

        handles += meter.counterBuilder("${namePrefix}events.accepted")
            .setDescription("Events admitted by ILogger")
            .buildWithCallback { measurement ->
                val delta = synchronized(lock) {
                    val current = logger.stats().accepted
                    (current - lastAccepted).also { lastAccepted = current }
                }
                if (delta > 0) measurement.record(delta)
            }

        handles += meter.counterBuilder("${namePrefix}events.delivered")
            .setDescription("Events delivered to at least one sink")
            .buildWithCallback { measurement ->
                val delta = synchronized(lock) {
                    val current = logger.stats().delivered
                    (current - lastDelivered).also { lastDelivered = current }
                }
                if (delta > 0) measurement.record(delta)
            }

        handles += meter.counterBuilder("${namePrefix}events.truncated")
            .setDescription("Events rewritten by configured limits")
            .buildWithCallback { measurement ->
                val delta = synchronized(lock) {
                    val current = logger.stats().truncated
                    (current - lastTruncated).also { lastTruncated = current }
                }
                if (delta > 0) measurement.record(delta)
            }

        handles += meter.counterBuilder("${namePrefix}events.dropped")
            .setDescription("Events lost before delivery")
            .buildWithCallback { measurement ->
                val deltas = synchronized(lock) {
                    val current = logger.stats().droppedByReason
                    val delta = current.mapValues { (reason, value) ->
                        value - (lastDropped[reason] ?: 0L)
                    }
                    lastDropped = current
                    delta
                }
                for ((reason, delta) in deltas) {
                    if (delta > 0) measurement.record(delta, attributesFor(reason))
                }
            }

        handles += meter.gaugeBuilder("${namePrefix}queue.depth")
            .ofLongs()
            .setDescription("Events waiting in per-sink queues")
            .buildWithCallback { measurement ->
                measurement.record(logger.stats().pending.toLong())
            }

        handles += meter.gaugeBuilder("${namePrefix}events.in_flight")
            .ofLongs()
            .setDescription("Events currently being written to a sink")
            .buildWithCallback { measurement ->
                measurement.record(logger.stats().inFlight.toLong())
            }

        handles += meter.gaugeBuilder("${namePrefix}binding.pending")
            .ofLongs()
            .setDescription("Queue depth of one sink binding")
            .buildWithCallback { measurement -> recordBindings(measurement) { it.pending } }

        handles += meter.gaugeBuilder("${namePrefix}binding.in_flight")
            .ofLongs()
            .setDescription("In-flight events of one sink binding")
            .buildWithCallback { measurement -> recordBindings(measurement) { it.inFlight } }
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            handles.toList().also { handles.clear() }
        }
        for (handle in toClose) {
            handle.close()
        }
    }

    private fun recordBindings(
        measurement: ObservableLongMeasurement,
        value: (BindingStats) -> Int,
    ) {
        for (binding in logger.stats().bindings) {
            measurement.record(
                value(binding).toLong(),
                Attributes.builder()
                    .put(AttributeKey.longKey("binding.index"), binding.index.toLong())
                    .put(AttributeKey.stringKey("binding.sink"), binding.sink)
                    .put(
                        AttributeKey.stringKey("binding.category"),
                        binding.category ?: "*",
                    )
                    .build(),
            )
        }
    }

    private fun attributesFor(reason: DropReason): Attributes =
        Attributes.of(AttributeKey.stringKey("reason"), reason.name)

}
