package io.ilogger.otel

import io.ilogger.annotations.LogLevel
import io.ilogger.core.EventLimits
import io.ilogger.core.LimitAction
import io.ilogger.testing.TestKit
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtelLoggerMetricsTest {

    private class Harness(
        configure: io.ilogger.core.ILoggerBuilder.() -> Unit = {},
    ) : AutoCloseable {
        val reader: InMemoryMetricReader = InMemoryMetricReader.create()
        val provider: SdkMeterProvider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build()
        val kit: TestKit = TestKit.create(configure = configure)
        val metrics: OtelLoggerMetrics = OtelLoggerMetrics(kit.logger, provider.get("test"))

        fun collect(): Map<String, MetricData> =
            reader.collectAllMetrics().associateBy { it.name }

        override fun close() {
            metrics.close()
            kit.logger.close()
            provider.close()
        }
    }

    @Test
    fun `reports accepted and delivered deltas`() = Harness().use { harness ->
        runBlocking {
            harness.kit.logger.record(action = "task.created")
            harness.kit.logger.record(action = "task.updated")
        }

        val first = harness.collect()
        assertEquals(2.0, sum(first, "ilogger.events.accepted"), 0.0)
        assertEquals(2.0, sum(first, "ilogger.events.delivered"), 0.0)

        runBlocking { harness.kit.logger.record(action = "task.deleted") }
        val second = harness.collect()
        assertEquals(1.0, sum(second, "ilogger.events.accepted"), 0.0)
        assertEquals(1.0, sum(second, "ilogger.events.delivered"), 0.0)
    }

    @Test
    fun `reports queue and binding gauges`() = Harness().use { harness ->
        val metrics = harness.collect()

        assertEquals(0.0, sum(metrics, "ilogger.queue.depth"), 0.0)
        assertEquals(0.0, sum(metrics, "ilogger.events.in_flight"), 0.0)
        val pending = metrics.getValue("ilogger.binding.pending").longGaugeData.points.single()
        assertEquals(0.0, pending.value.toDouble(), 0.0)
        assertEquals(
            harness.kit.sink.javaClass.name,
            pending.attributes.get(AttributeKey.stringKey("binding.sink")),
        )
        assertEquals("*", pending.attributes.get(AttributeKey.stringKey("binding.category")))
        assertTrue(metrics.containsKey("ilogger.binding.in_flight"))
    }

    @Test
    fun `attributes dropped events by reason`() {
        Harness {
            sampling(LogLevel.INFO to 0.0)
        }.use { harness ->
            harness.kit.logger.info("filtered")

            val dropped = harness.collect().getValue("ilogger.events.dropped")
            val point = dropped.longSumData.points.single()
            assertEquals(1.0, point.value.toDouble(), 0.0)
            assertEquals("SAMPLED", point.attributes.get(AttributeKey.stringKey("reason")))
        }
    }

    @Test
    fun `reports truncated events`() {
        Harness {
            limits = EventLimits(maxStringLength = 16, action = LimitAction.TRUNCATE)
        }.use { harness ->
            runBlocking {
                harness.kit.logger.record(
                    action = "task.created",
                    metadata = mapOf("note" to "x".repeat(100)),
                )
            }

            assertEquals(1.0, sum(harness.collect(), "ilogger.events.truncated"), 0.0)
        }
    }

    @Test
    fun `close unregisters every instrument`() = Harness().use { harness ->
        assertTrue(harness.collect().isNotEmpty())
        harness.metrics.close()
        assertFalse(harness.collect().containsKey("ilogger.events.accepted"))
    }

    @Test
    fun `supports a custom name prefix`() {
        val reader = InMemoryMetricReader.create()
        val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        val kit = TestKit.create()
        try {
            OtelLoggerMetrics(kit.logger, provider.get("test"), namePrefix = "audit.").use {
                runBlocking { kit.logger.record(action = "task.created") }
                val metrics = reader.collectAllMetrics()
                assertTrue(metrics.any { it.name == "audit.events.accepted" })
            }
        } finally {
            kit.logger.close()
            provider.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty name prefix`() {
        val kit = TestKit.create()
        try {
            OtelLoggerMetrics(kit.logger, SdkMeterProvider.builder().build().get("test"), namePrefix = "")
        } finally {
            kit.logger.close()
        }
    }

    private fun sum(metrics: Map<String, MetricData>, name: String): Double =
        metrics.getValue(name).longSumData.points.sumOf { it.value }.toDouble()
}
