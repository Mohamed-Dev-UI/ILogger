package io.ilogger.otel

import io.ilogger.core.LogContext
import io.ilogger.testing.TestKit
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtelTraceEnricherTest {

    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val spanId = "00f067aa0ba902b7"

    private fun provider(): SdkTracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
            .build()

    @Test
    fun `captures the current span`() {
        val provider = provider()
        try {
            val span = provider.get("test").spanBuilder("op").startSpan()
            try {
                span.makeCurrent().use {
                    val metadata = OtelTraceEnricher().enrich(LogContext())
                    assertEquals(span.spanContext.traceId, metadata["trace.traceId"])
                    assertEquals(span.spanContext.spanId, metadata["trace.spanId"])
                    assertEquals(true, metadata["trace.sampled"])
                    assertEquals(false, metadata["trace.remote"])
                    assertEquals(
                        W3cTraceParent(
                            span.spanContext.traceId,
                            span.spanContext.spanId,
                            sampled = true,
                        ).format(),
                        metadata["trace.traceparent"],
                    )
                }
            } finally {
                span.end()
            }
        } finally {
            provider.close()
        }
    }

    @Test
    fun `marks remote parents`() {
        val parent = W3cTraceParent(traceId, spanId, sampled = true)
        Span.wrap(parent.toSpanContext()).makeCurrent().use {
            val metadata = OtelTraceEnricher().enrich(LogContext())
            assertEquals(traceId, metadata["trace.traceId"])
            assertEquals(spanId, metadata["trace.spanId"])
            assertEquals(true, metadata["trace.remote"])
        }
    }

    @Test
    fun `falls back to the context traceparent`() {
        val parent = W3cTraceParent(traceId, spanId, sampled = false)
        val metadata = OtelTraceEnricher().enrich(
            LogContext(attributes = parent.toContextAttributes()),
        )
        assertEquals(traceId, metadata["trace.traceId"])
        assertEquals(spanId, metadata["trace.spanId"])
        assertEquals(false, metadata["trace.sampled"])
        assertEquals(true, metadata["trace.remote"])
    }

    @Test
    fun `returns nothing without a trace`() {
        assertTrue(OtelTraceEnricher().enrich(LogContext()).isEmpty())
        assertTrue(
            OtelTraceEnricher().enrich(
                LogContext(attributes = mapOf("traceparent" to "garbage")),
            ).isEmpty(),
        )
    }

    @Test
    fun `supports a custom metadata prefix`() {
        val parent = W3cTraceParent(traceId, spanId)
        val metadata = OtelTraceEnricher(metadataPrefix = "otel.")
            .enrich(LogContext(attributes = parent.toContextAttributes()))
        assertEquals(traceId, metadata["otel.traceId"])
        assertEquals(spanId, metadata["otel.spanId"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a blank prefix`() {
        OtelTraceEnricher(metadataPrefix = " ")
    }

    @Test
    fun `logger events carry trace metadata under inline dispatch`() = runBlocking {
        val provider = provider()
        val kit = TestKit.create { enricher(OtelTraceEnricher()) }
        try {
            val span = provider.get("test").spanBuilder("checkout").startSpan()
            try {
                span.makeCurrent().use {
                    kit.logger.record(action = "order.placed")
                    kit.logger.info("checkout done")
                }
            } finally {
                span.end()
            }

            assertEquals(2, kit.sink.count())
            val audit = kit.sink.events().first { it.action == "order.placed" }
            val diagnostic = kit.sink.events().first { it.action == "log.info" }
            assertEquals(span.spanContext.traceId, audit.metadata["trace.traceId"])
            assertEquals(span.spanContext.spanId, diagnostic.metadata["trace.spanId"])
        } finally {
            provider.close()
            kit.logger.close()
        }
    }
}
