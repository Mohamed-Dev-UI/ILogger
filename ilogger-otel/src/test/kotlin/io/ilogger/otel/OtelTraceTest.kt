package io.ilogger.otel

import io.ilogger.core.LogContexts
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtelTraceTest {

    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val spanId = "00f067aa0ba902b7"
    private val parent = W3cTraceParent(traceId, spanId, sampled = true)

    private fun provider(): SdkTracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
            .build()

    @Test
    fun `withRemoteParent makes the remote span current and publishes context`() = runBlocking {
        OtelTrace.withRemoteParent(parent) {
            val current = Span.current().spanContext
            assertTrue(current.isValid)
            assertEquals(traceId, current.traceId)
            assertEquals(spanId, current.spanId)
            assertTrue(current.isRemote)
            assertEquals(
                parent.format(),
                LogContexts.current().attributes[OtelTrace.TRACEPARENT_ATTRIBUTE],
            )
        }
        assertNull(OtelTrace.currentSpanContext())
    }

    @Test
    fun `withIncomingTraceParent accepts a valid header`() = runBlocking {
        OtelTrace.withIncomingTraceParent("00-$traceId-$spanId-00") {
            assertEquals(traceId, Span.current().spanContext.traceId)
            assertFalse(Span.current().spanContext.traceFlags.isSampled)
        }
    }

    @Test
    fun `withIncomingTraceParent ignores null and malformed headers`() = runBlocking {
        OtelTrace.withIncomingTraceParent(null) {
            assertNull(OtelTrace.currentSpanContext())
            assertTrue(LogContexts.current().attributes.isEmpty())
        }
        OtelTrace.withIncomingTraceParent("not-a-traceparent") {
            assertNull(OtelTrace.currentSpanContext())
            assertTrue(LogContexts.current().attributes.isEmpty())
        }
    }

    @Test
    fun `withCurrentTrace publishes the recording span`() = runBlocking {
        val provider = provider()
        try {
            val span = provider.get("test").spanBuilder("op").startSpan()
            try {
                span.makeCurrent().use {
                    val current = OtelTrace.currentTraceParent()
                    assertEquals(traceId.length, current!!.traceId.length)
                    assertEquals(span.spanContext.spanId, current.spanId)
                    OtelTrace.withCurrentTrace {
                        assertEquals(
                            current.format(),
                            LogContexts.current().attributes[OtelTrace.TRACEPARENT_ATTRIBUTE],
                        )
                    }
                }
            } finally {
                span.end()
            }
        } finally {
            provider.close()
        }
    }

    @Test
    fun `current helpers return empty without a span`() {
        assertNull(OtelTrace.currentSpanContext())
        assertNull(OtelTrace.currentTraceParent())
        assertTrue(OtelTrace.currentContextAttributes().isEmpty())
    }

    @Test
    fun `withCurrentTrace passes through without a span`() = runBlocking {
        val marker = OtelTrace.withCurrentTrace {
            assertTrue(LogContexts.current().attributes.isEmpty())
            "done"
        }
        assertEquals("done", marker)
    }
}
