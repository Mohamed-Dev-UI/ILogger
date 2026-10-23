package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventEnrichmentTest {

    private class Harness(
        vararg enrichers: LogEventEnricher,
    ) : AutoCloseable {
        val sink = RecordingSink()
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
            onDispatchError = { _, _, error -> errors += error }
            enrichers.forEach { enricher(it) }
        }

        override fun close() {
            logger.close()
        }
    }

    @Test
    fun `enricher metadata is merged into the event`() = runBlocking {
        Harness(LogEventEnricher { mapOf("trace.traceId" to "abc", "trace.spanId" to "def") }).use { harness ->
            harness.logger.record(action = "task.created", metadata = mapOf("taskId" to "t1"))

            val metadata = harness.sink.events.single().metadata
            assertEquals("abc", metadata["trace.traceId"])
            assertEquals("def", metadata["trace.spanId"])
            assertEquals("t1", metadata["taskId"])
        }
    }

    @Test
    fun `explicit event metadata wins over enricher output`() = runBlocking {
        Harness(LogEventEnricher { mapOf("trace.traceId" to "from-enricher", "tenant" to "t") }).use { harness ->
            harness.logger.record(
                action = "task.created",
                metadata = mapOf("trace.traceId" to "explicit"),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("explicit", metadata["trace.traceId"])
            assertEquals("t", metadata["tenant"])
        }
    }

    @Test
    fun `the first registered enricher wins on key conflicts`() = runBlocking {
        Harness(
            LogEventEnricher { mapOf("origin" to "first") },
            LogEventEnricher { mapOf("origin" to "second", "extra" to "kept") },
        ).use { harness ->
            harness.logger.record(action = "task.created")

            val metadata = harness.sink.events.single().metadata
            assertEquals("first", metadata["origin"])
            assertEquals("kept", metadata["extra"])
        }
    }

    @Test
    fun `enricher sees the ambient coroutine context`() = runBlocking {
        Harness(LogEventEnricher { context -> mapOf("tenant" to context.attributes["tenantId"]) }).use { harness ->
            withContextAttributes("tenantId" to "acme") {
                harness.logger.record(action = "task.created")
            }

            assertEquals("acme", harness.sink.events.single().metadata["tenant"])
        }
    }

    @Test
    fun `enricher sees the thread local context on blocking paths`() {
        Harness(LogEventEnricher { context -> mapOf("tenant" to context.correlationId) }).use { harness ->
            LogContexts.onCurrentThread(LogContext(correlationId = "req-1")) {
                harness.logger.recordNow(action = "task.created", dispatch = DispatchMode.INLINE)
            }

            assertEquals("req-1", harness.sink.events.single().metadata["tenant"])
        }
    }

    @Test
    fun `diagnostics are enriched too`() {
        Harness(LogEventEnricher { mapOf("trace.traceId" to "abc") }).use { harness ->
            harness.logger.info("hello")

            assertEquals("abc", harness.sink.events.single().metadata["trace.traceId"])
        }
    }

    @Test
    fun `throwing enricher is reported and the event is still recorded`() = runBlocking {
        Harness(
            LogEventEnricher { throw IllegalStateException("boom") },
            LogEventEnricher { mapOf("survives" to true) },
        ).use { harness ->
            harness.logger.record(action = "task.created")

            val event = harness.sink.events.single()
            assertEquals(true, event.metadata["survives"])
            assertFalse(event.metadata.containsKey("missing"))
            assertEquals("boom", harness.errors.single().message)
        }
    }

    @Test
    fun `enriched values flow through redaction`() = runBlocking {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
            redaction = RedactionPolicy.build {
                rule(RedactionRules.secretKeys())
            }
            enricher { mapOf("api_key" to "sk-live-123") }
        }
        try {
            logger.record(action = "task.created")

            assertEquals("***", sink.events.single().metadata["api_key"])
        } finally {
            logger.close()
        }
    }

    @Test
    fun `enricher values pass through value adapters`() = runBlocking {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
            valueAdapter(java.time.Instant::class) { instant -> instant.toString() }
            enricher { mapOf("startedAt" to java.time.Instant.EPOCH) }
        }
        try {
            logger.record(action = "task.created")

            assertEquals("1970-01-01T00:00:00Z", sink.events.single().metadata["startedAt"])
        } finally {
            logger.close()
        }
    }

    @Test
    fun `no enrichers leaves metadata untouched`() = runBlocking {
        Harness().use { harness ->
            val supplied = mapOf<String, Any?>("taskId" to "t1")
            harness.logger.record(action = "task.created", metadata = supplied)

            assertEquals(supplied, harness.sink.events.single().metadata)
            assertTrue(harness.errors.isEmpty())
        }
    }
}
