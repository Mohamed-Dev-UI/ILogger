package io.ilogger.otel

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.DirectLogSink
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.testing.InMemoryLogSink
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtelSinkMetricsTest {

    private class FlakySink(
        var failures: Int,
        private val failure: () -> Throwable = { IOException("down") },
    ) : LogSink {
        val events = ArrayList<LogEvent>()
        var flushCount = 0
        var closed = false

        override suspend fun write(event: LogEvent) {
            if (failures > 0) {
                failures--
                throw failure()
            }
            events += event
        }

        override suspend fun flush() {
            flushCount++
        }

        override fun close() {
            closed = true
        }
    }

    private class Harness {
        val reader: InMemoryMetricReader = InMemoryMetricReader.create()
        val provider: SdkMeterProvider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build()

        fun meter() = provider.get("test")

        fun collect(): Map<String, MetricData> =
            reader.collectAllMetrics().associateBy { it.name }

        fun close() {
            provider.close()
        }
    }

    private fun event(id: String = "e1", action: String = "task.created"): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.AUDIT,
        action = action,
    )

    @Test
    fun `counts failures, retries and successful events`() {
        val harness = Harness()
        try {
            val sink = FlakySink(failures = 1)
            val wrapped = OtelSinkMetrics.wrap(sink, harness.meter(), sinkName = "flaky")
            val logEvent = event()

            runBlocking {
                try {
                    wrapped.write(logEvent)
                } catch (_: IOException) {
                    // expected first attempt
                }
                wrapped.write(logEvent)
            }

            val metrics = harness.collect()
            val errorPoint = metrics.getValue("ilogger.sink.errors").longSumData.points.single()
            assertEquals(1L, errorPoint.value)
            assertEquals("flaky", errorPoint.attributes.get(AttributeKey.stringKey("sink")))
            assertEquals(
                IOException::class.java.name,
                errorPoint.attributes.get(AttributeKey.stringKey("error.type")),
            )
            assertEquals(1.0, sum(metrics, "ilogger.sink.retries"), 0.0)
            assertEquals(1.0, sum(metrics, "ilogger.sink.events"), 0.0)
            val duration = metrics.getValue("ilogger.sink.write.duration").histogramData.points
            assertEquals(2L, duration.sumOf { it.count })
            assertTrue(duration.sumOf { it.sum } >= 0.0)
            assertEquals(
                setOf("success", "error"),
                duration.mapNotNull { it.attributes.get(AttributeKey.stringKey("outcome")) }.toSet(),
            )
            assertEquals(1, sink.events.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `counts a whole batch as retried after a failed attempt`() {
        val harness = Harness()
        try {
            val sink = FlakySink(failures = 1)
            val wrapped = OtelSinkMetrics.wrap(sink, harness.meter(), sinkName = "flaky")
            val batch = listOf(event("e1"), event("e2"), event("e3"))

            runBlocking {
                try {
                    wrapped.writeAll(batch)
                } catch (_: IOException) {
                    // expected
                }
                wrapped.writeAll(batch)
            }

            assertEquals(3.0, sum(harness.collect(), "ilogger.sink.retries"), 0.0)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `writeAll counts events and one duration sample`() {
        val harness = Harness()
        try {
            val sink = FlakySink(failures = 0)
            val wrapped = OtelSinkMetrics.wrap(sink, harness.meter(), sinkName = "batch")
            runBlocking {
                wrapped.writeAll(listOf(event("e1"), event("e2"), event("e3")))
            }

            val metrics = harness.collect()
            assertEquals(3.0, sum(metrics, "ilogger.sink.events"), 0.0)
            val duration = metrics.getValue("ilogger.sink.write.duration").histogramData.points.single()
            assertEquals(1L, duration.count)
            assertEquals("success", duration.attributes.get(AttributeKey.stringKey("outcome")))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `cancellation is not counted as a sink error`() {
        val harness = Harness()
        try {
            val sink = FlakySink(failures = 1, failure = { CancellationException("stop") })
            val wrapped = OtelSinkMetrics.wrap(sink, harness.meter(), sinkName = "cancelled")

            runBlocking {
                try {
                    wrapped.write(event())
                } catch (_: CancellationException) {
                    // expected
                }
            }

            val metrics = harness.collect()
            assertFalse(metrics.containsKey("ilogger.sink.errors"))
            val duration = metrics.getValue("ilogger.sink.write.duration").histogramData.points.single()
            assertEquals("cancelled", duration.attributes.get(AttributeKey.stringKey("outcome")))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `wrap preserves the direct sink fast path`() {
        val harness = Harness()
        val inMemory = InMemoryLogSink()
        val logger = ILogger.create {
            sink(OtelSinkMetrics.wrap(inMemory, harness.meter(), sinkName = "memory"))
            dispatch = DispatchMode.INLINE
        }
        try {
            val wrapped = OtelSinkMetrics.wrap(inMemory, harness.meter())
            assertTrue(wrapped is DirectLogSink)

            logger.info("inline diagnostic")

            assertEquals(1, inMemory.count())
            assertEquals(1.0, sum(harness.collect(), "ilogger.sink.events"), 0.0)
        } finally {
            logger.close()
            harness.close()
        }
    }

    @Test
    fun `flush and close delegate`() {
        val harness = Harness()
        try {
            val sink = FlakySink(failures = 0)
            val wrapped = OtelSinkMetrics.wrap(sink, harness.meter())
            runBlocking { wrapped.flush() }
            wrapped.close()

            assertEquals(1, sink.flushCount)
            assertTrue(sink.closed)
        } finally {
            harness.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a blank sink name`() {
        OtelSinkMetrics(Sink(), SdkMeterProvider.builder().build().get("test"), sinkName = " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty name prefix`() {
        OtelSinkMetrics(Sink(), SdkMeterProvider.builder().build().get("test"), namePrefix = "")
    }

    private class Sink : LogSink {
        override suspend fun write(event: LogEvent): Unit = Unit
    }

    private fun sum(metrics: Map<String, MetricData>, name: String): Double =
        metrics.getValue(name).longSumData.points.sumOf { it.value }.toDouble()
}
