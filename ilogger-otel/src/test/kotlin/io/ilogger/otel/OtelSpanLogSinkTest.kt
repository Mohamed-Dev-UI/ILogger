package io.ilogger.otel

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.Outcome
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtelSpanLogSinkTest {

    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val spanId = "00f067aa0ba902b7"

    private class Harness : AutoCloseable {
        val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
        val provider: SdkTracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()

        override fun close() {
            provider.close()
        }
    }

    private fun event(
        metadata: Map<String, Any?> = emptyMap(),
        outcome: Outcome = Outcome.SUCCESS,
        failure: LogFailure? = null,
    ): LogEvent = LogEvent(
        id = "e1",
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.AUDIT,
        action = "task.created",
        entity = "Task",
        entityId = "t1",
        actor = io.ilogger.core.Actor.user("u1"),
        metadata = metadata,
        outcome = outcome,
        failure = failure,
        source = "test",
        category = "audit",
        correlationId = "req-1",
    )

    @Test
    fun `exports an event as a child span of its trace`() = Harness().use { harness ->
        val sink = OtelSpanLogSink(harness.provider.get("test"))
        runBlocking {
            sink.write(
                event(
                    metadata = mapOf(
                        "trace.traceId" to traceId,
                        "trace.spanId" to spanId,
                        "trace.sampled" to true,
                        "taskId" to "t1",
                    ),
                ),
            )
        }

        val span = harness.exporter.finishedSpanItems.single()
        assertEquals("ilogger.task.created", span.name)
        assertEquals(traceId, span.traceId)
        assertTrue(span.parentSpanContext.isValid)
        assertEquals(spanId, span.parentSpanContext.spanId)
        assertEquals("e1", span.attributes.get(AttributeKey.stringKey("ilogger.event.id")))
        assertEquals("task.created", span.attributes.get(AttributeKey.stringKey("ilogger.action")))
        assertEquals("AUDIT", span.attributes.get(AttributeKey.stringKey("ilogger.level")))
        assertEquals("SUCCESS", span.attributes.get(AttributeKey.stringKey("ilogger.outcome")))
        assertEquals("Task", span.attributes.get(AttributeKey.stringKey("ilogger.entity")))
        assertEquals("t1", span.attributes.get(AttributeKey.stringKey("ilogger.entityId")))
        assertEquals("USER", span.attributes.get(AttributeKey.stringKey("ilogger.actor.type")))
        assertEquals("u1", span.attributes.get(AttributeKey.stringKey("ilogger.actor.id")))
        assertEquals("req-1", span.attributes.get(AttributeKey.stringKey("ilogger.correlationId")))
        assertEquals("t1", span.attributes.get(AttributeKey.stringKey("ilogger.meta.taskId")))
        assertEquals(StatusCode.OK, span.status.statusCode)
        assertEquals(1_700_000_000_000L * 1_000_000, span.startEpochNanos)
        assertEquals(span.startEpochNanos, span.endEpochNanos)
    }

    @Test
    fun `events without trace metadata become root spans`() = Harness().use { harness ->
        val sink = OtelSpanLogSink(harness.provider.get("test"))
        runBlocking { sink.write(event()) }

        val span = harness.exporter.finishedSpanItems.single()
        assertFalse(span.parentSpanContext.isValid)
        assertTrue(span.traceId != traceId)
    }

    @Test
    fun `failure outcomes are error spans`() = Harness().use { harness ->
        val sink = OtelSpanLogSink(harness.provider.get("test"))
        val failure = LogFailure.from(IllegalStateException("boom"))
        runBlocking { sink.write(event(outcome = Outcome.FAILURE, failure = failure)) }

        val span = harness.exporter.finishedSpanItems.single()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertEquals("boom", span.status.description)
        assertEquals(
            "java.lang.IllegalStateException",
            span.attributes.get(AttributeKey.stringKey("ilogger.failure.type")),
        )
        assertEquals(false, span.attributes.get(AttributeKey.booleanKey("ilogger.failure.cancelled")))
    }

    @Test
    fun `metadata export can be disabled or capped`() = Harness().use { harness ->
        val metadata = mapOf("a" to 1, "b" to 2, "c" to 3)
        val capped = OtelSpanLogSink(
            harness.provider.get("test"),
            maxMetadataEntries = 2,
        )
        runBlocking { capped.write(event(metadata = metadata)) }

        val span = harness.exporter.finishedSpanItems.single()
        val exported = span.attributes.asMap().keys.count { it.key.startsWith("ilogger.meta.") }
        assertEquals(2, exported)
    }

    @Test
    fun `metadata can be excluded entirely`() = Harness().use { harness ->
        val sink = OtelSpanLogSink(harness.provider.get("test"), includeMetadata = false)
        runBlocking { sink.write(event(metadata = mapOf("a" to 1))) }

        val span: SpanData = harness.exporter.finishedSpanItems.single()
        val exported = span.attributes.asMap().keys.count { it.key.startsWith("ilogger.meta.") }
        assertEquals(0, exported)
    }

    @Test
    fun `long values are truncated`() = Harness().use { harness ->
        val sink = OtelSpanLogSink(
            harness.provider.get("test"),
            maxAttributeLength = 10,
        )
        runBlocking { sink.write(event(metadata = mapOf("long" to "x".repeat(100)))) }

        val span = harness.exporter.finishedSpanItems.single()
        assertEquals("x".repeat(10), span.attributes.get(AttributeKey.stringKey("ilogger.meta.long")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a negative metadata cap`() {
        OtelSpanLogSink(maxMetadataEntries = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive attribute length`() {
        OtelSpanLogSink(maxAttributeLength = 0)
    }
}
