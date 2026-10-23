package io.ilogger.serialization

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.FieldChange
import io.ilogger.core.HashChainSink
import io.ilogger.core.HmacTrailSigner
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.LogSink
import io.ilogger.core.Outcome
import io.ilogger.core.SigningKey
import io.ilogger.core.TrailVerifier
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonEventCodecIntegrityTest {

    private class CollectingSink : LogSink {
        val events = Collections.synchronizedList(ArrayList<LogEvent>())

        override suspend fun write(event: LogEvent) {
            events.add(event)
        }
    }

    private val key = SigningKey("k1", HmacTrailSigner("secret".toByteArray()))

    private fun event(action: String, metadata: Map<String, Any?> = emptyMap()): LogEvent = LogEvent(
        id = "evt-$action",
        timestampMillis = 1_700_000_000_000L,
        level = LogLevel.AUDIT,
        action = action,
        entity = "Task",
        entityId = "42",
        source = "TaskDao.insert",
        metadata = metadata,
    )

    @Test
    fun `decode reverses encode`() {
        val event = LogEvent(
            id = "e1",
            timestampMillis = 1_700_000_000_000L,
            level = LogLevel.AUDIT,
            action = "task.created",
            actor = Actor.user("u1", "Alice", mapOf("dept" to "ops")),
            entity = "Task",
            entityId = "42",
            source = "TaskDao.insert",
            category = "billing",
            correlationId = "c1",
            causationId = "ca1",
            metadata = mapOf(
                "title" to "x",
                "count" to 3L,
                "ratio" to 0.5,
                "flag" to true,
                "nested" to mapOf("a" to 1L),
                "list" to listOf(1L, "two"),
            ),
            changes = listOf(FieldChange("status", "open", "done")),
            outcome = Outcome.FAILURE,
        )

        assertEquals(event, JsonEventCodec.decode(JsonEventCodec.encode(event)))
    }

    @Test
    fun `decode restores failure details`() {
        val event = LogEvent(
            id = "e2",
            timestampMillis = 1_700_000_000_000L,
            level = LogLevel.AUDIT,
            action = "task.failed",
            outcome = Outcome.FAILURE,
            failure = LogFailure(
                errorType = "java.lang.IllegalStateException",
                message = "boom",
                rootCauseType = "java.lang.RuntimeException",
                stackSummary = listOf("at App.main(App.kt:10)"),
            ),
        )

        val decoded = JsonEventCodec.decode(JsonEventCodec.encode(event))
        assertEquals(event.failure!!.errorType, decoded.failure!!.errorType)
        assertEquals(event.failure!!.message, decoded.failure!!.message)
        assertEquals(event.failure!!.rootCauseType, decoded.failure!!.rootCauseType)
        assertEquals(event.failure!!.stackSummary, decoded.failure!!.stackSummary)
        assertEquals(Outcome.FAILURE, decoded.outcome)
    }

    @Test
    fun `decode applies documented defaults`() {
        val decoded = JsonEventCodec.decode("""{"id":"e3","timestampMillis":1700000000000,"action":"x"}""")
        assertEquals("e3", decoded.id)
        assertEquals(LogLevel.AUDIT, decoded.level)
        assertEquals(1, decoded.actionVersion)
        assertEquals(Outcome.SUCCESS, decoded.outcome)
        assertTrue(decoded.metadata.isEmpty())
    }

    @Test
    fun `decode rejects malformed lines`() {
        assertThrows(IllegalArgumentException::class.java) { JsonEventCodec.decode("not json") }
        assertThrows(IllegalArgumentException::class.java) { JsonEventCodec.decode("""[1,2,3]""") }
        assertThrows(IllegalArgumentException::class.java) {
            JsonEventCodec.decode("""{"id":"e4","timestampMillis":1}""")
        }
    }

    @Test
    fun `decode maps every actor type and null metadata`() {
        val actors = listOf(
            Actor.user("u1"),
            Actor.system(),
            Actor.worker("w1"),
            Actor.automation("a1"),
            Actor.plugin("p1"),
            Actor.service("s1"),
            Actor.anonymous(),
            Actor.custom("c1", "Cust"),
        )
        actors.forEach { actor ->
            val event = LogEvent(
                id = "e-${actor.type.name.lowercase()}",
                timestampMillis = 1_700_000_000_000L,
                level = LogLevel.AUDIT,
                action = "a",
                actor = actor,
            )
            assertEquals(actor, JsonEventCodec.decode(JsonEventCodec.encode(event)).actor)
        }

        val withNull = LogEvent(
            id = "e-null",
            timestampMillis = 1_700_000_000_000L,
            level = LogLevel.AUDIT,
            action = "a",
            metadata = mapOf<String, Any?>("n" to null),
        )
        assertEquals(withNull, JsonEventCodec.decode(JsonEventCodec.encode(withNull)))
    }

    @Test
    fun `decode rejects inconsistent nested members`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonEventCodec.decode("""{"id":"e","timestampMillis":1,"action":"a","actor":{"type":"user"}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonEventCodec.decode("""{"id":"e","timestampMillis":1,"action":"a","failure":{"message":"m"}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonEventCodec.decode("""{"id":"e","timestampMillis":1,"action":"a","changes":[{"before":1,"after":2}]}""")
        }
    }

    @Test
    fun `stamped events verify after a JSONL round trip`() = runBlocking {
        val sink = CollectingSink()
        val chained = HashChainSink(sink, key)
        chained.write(event("a", metadata = mapOf("amount" to 10L, "nested" to mapOf("user" to "bob"))))
        chained.write(event("b", metadata = mapOf("items" to listOf(1L, 2L))))
        chained.write(event("c"))
        chained.close()

        val reparsed = sink.events.map { JsonEventCodec.decode(JsonEventCodec.encode(it)) }
        val result = TrailVerifier.verify(reparsed, mapOf("k1" to key.signer))
        assertTrue(result.reason, result.valid)
    }

    @Test
    fun `editing a JSON line breaks verification`() = runBlocking {
        val sink = CollectingSink()
        val chained = HashChainSink(sink, key)
        chained.write(event("payment.charged", metadata = mapOf("amount" to 10L)))
        chained.close()

        val line = JsonEventCodec.encode(sink.events[0])
        assertTrue("fixture must contain the amount", line.contains("\"amount\":10"))
        val edited = line.replace("\"amount\":10", "\"amount\":9999")

        val result = TrailVerifier.verify(listOf(JsonEventCodec.decode(edited)), mapOf("k1" to key.signer))
        assertFalse(result.valid)
    }
}
