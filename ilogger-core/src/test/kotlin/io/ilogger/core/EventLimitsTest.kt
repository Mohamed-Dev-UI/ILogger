package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLimitsTest {

    private class Harness(limits: EventLimits) : AutoCloseable {
        val sink = RecordingSink()
        val drops = Collections.synchronizedList(ArrayList<DroppedEvent>())
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
            this.limits = limits
            onDrop = { drops += it }
        }

        override fun close() {
            logger.close()
        }
    }

    @Test
    fun `reject policy drops oversized strings and reports the reason`() = runBlocking {
        Harness(EventLimits(maxStringLength = 10)).use { harness ->
            harness.logger.record(
                action = "task.add",
                metadata = mapOf("title" to "x".repeat(11)),
            )
            harness.logger.record(
                action = "task.add",
                metadata = mapOf("title" to "short"),
            )

            assertEquals(listOf("task.add"), harness.sink.actions())
            val drop = harness.drops.single()
            assertEquals(DropReason.STRICT_REJECT, drop.reason)
            assertNull("sampling/allocation never happened", drop.event)
            assertTrue(drop.error!!.message!!.startsWith("ilogger limits:"))
            assertEquals(0, harness.logger.stats().truncated)
        }
    }

    @Test
    fun `truncate shortens strings, marks the event and counts it`() = runBlocking {
        Harness(EventLimits(maxStringLength = 20, action = LimitAction.TRUNCATE)).use { harness ->
            harness.logger.record(
                action = "task.created",
                metadata = mapOf(
                    "title" to "x".repeat(100),
                    "nested" to listOf("y".repeat(100), 1, 2),
                ),
            )

            val event = harness.sink.events.single()
            val title = event.metadata["title"] as String
            assertEquals(20, title.length)
            assertTrue(title.endsWith(EventLimits.TRUNCATION_MARKER))
            assertEquals(true, event.metadata[EventLimits.TRUNCATED_KEY])
            val nested = event.metadata["nested"] as List<*>
            assertTrue((nested[0] as String).endsWith(EventLimits.TRUNCATION_MARKER))
            assertEquals(1, nested[1])
            assertEquals(1L, harness.logger.stats().truncated)
        }
    }

    @Test
    fun `truncate bounds entry counts and actor attributes`() = runBlocking {
        val limits = EventLimits(
            maxEntries = 3,
            maxActorAttributes = 2,
            action = LimitAction.TRUNCATE,
        )
        Harness(limits).use { harness ->
            harness.logger.record(
                action = "task.updated",
                actor = Actor.user(
                    "u1",
                    name = "A very long user name that exceeds nothing",
                    attributes = mapOf("team" to "core", "region" to "eu", "extra" to "dropped"),
                ),
                metadata = mapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4),
            )

            val event = harness.sink.events.single()
            assertTrue("metadata stays within maxEntries", event.metadata.size <= 3)
            assertEquals(true, event.metadata[EventLimits.TRUNCATED_KEY])
            assertEquals(2, event.actor!!.attributes.size)
            assertEquals(ActorType.USER, event.actor.type)
            assertEquals("u1", event.actor.id)
        }
    }

    @Test
    fun `failure frames and messages are bounded at capture and truncation`() = runBlocking {
        fun deep(depth: Int): Nothing =
            if (depth == 0) throw IllegalStateException("m".repeat(100)) else deep(depth - 1)

        val error = runCatching { deep(50) }.exceptionOrNull()!!
        val limits = EventLimits(maxFailureFrames = 3, maxStringLength = 20, action = LimitAction.TRUNCATE)
        Harness(limits).use { harness ->
            harness.logger.record(
                action = "job.failed",
                level = LogLevel.ERROR,
                outcome = Outcome.FAILURE,
                failure = error,
            )

            val failure = harness.sink.events.single().failure
            assertNotNull(failure)
            assertEquals(3, failure!!.stackSummary.size)
            assertTrue(failure.stackSummary.all { it.length <= 20 })
            assertTrue(failure.message!!.endsWith(EventLimits.TRUNCATION_MARKER))
        }
    }

    @Test
    fun `the action itself is never truncated`() = runBlocking {
        Harness(EventLimits(maxStringLength = 8, action = LimitAction.TRUNCATE)).use { harness ->
            harness.logger.record(action = "very.long.action")

            assertTrue(harness.sink.events.isEmpty())
            assertEquals(DropReason.STRICT_REJECT, harness.drops.single().reason)
        }
    }

    @Test
    fun `cyclic and deeply nested values terminate within the depth bound`() = runBlocking {
        val cyclic = LinkedHashMap<String, Any?>()
        cyclic["self"] = cyclic
        val deep: Any? = (0 until 100).fold<Int, Any?>("leaf") { nested, _ -> mapOf("n" to nested) }

        Harness(
            EventLimits(maxEntries = 16, maxStringLength = 64, maxEventBytes = 4_096, action = LimitAction.TRUNCATE),
        ).use { harness ->
            harness.logger.record(
                action = "cycle.recorded",
                metadata = mapOf("cycle" to cyclic, "deep" to deep),
            )

            val event = harness.sink.events.single()
            assertEquals(true, event.metadata[EventLimits.TRUNCATED_KEY])
            assertEquals(1L, harness.logger.stats().truncated)

            fun depthOf(value: Any?, depth: Int = 0): Int = when (value) {
                is Map<*, *> -> value.values.maxOfOrNull { depthOf(it, depth + 1) } ?: depth
                is Iterable<*> -> value.maxOfOrNull { depthOf(it, depth + 1) } ?: depth
                else -> depth
            }
            assertTrue("structure depth stays bounded", depthOf(event.metadata) <= 6)
        }
    }

    @Test
    fun `the byte budget stops a large payload in truncate mode`() = runBlocking {
        val metadata = LinkedHashMap<String, Any?>()
        repeat(64) { index -> metadata["key$index"] = "v".repeat(64) }

        val limits = EventLimits(
            maxEventBytes = 512,
            maxEntries = 64,
            maxStringLength = 64,
            action = LimitAction.TRUNCATE,
        )
        Harness(limits).use { harness ->
            harness.logger.record(action = "bulk.event", metadata = metadata)

            val event = harness.sink.events.single()
            assertTrue(event.metadata.size < 64)
            assertEquals(true, event.metadata[EventLimits.TRUNCATED_KEY])
            val renderedBytes = event.metadata.entries.sumOf { (key, value) ->
                key.length + ((value as? String)?.length ?: 0)
            }
            assertTrue("payload is bounded, was $renderedBytes", renderedBytes <= 512)
        }
    }

    @Test
    fun `disabled limits leave payloads untouched`() = runBlocking {
        val sink = RecordingSink()
        val unlimited = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
        }
        unlimited.use {
            it.record(action = "task.created", metadata = mapOf("title" to "x".repeat(1_000)))
        }
        val event = sink.events.single()
        assertEquals(1_000, (event.metadata["title"] as String).length)
    }
}
