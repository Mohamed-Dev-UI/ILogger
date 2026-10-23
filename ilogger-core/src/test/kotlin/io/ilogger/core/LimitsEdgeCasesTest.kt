@file:OptIn(ExperimentalUnsignedTypes::class)

package io.ilogger.core

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsEdgeCasesTest {

    private fun outcome(
        limits: EventLimits,
        action: String = "a",
        actor: Actor? = null,
        entity: String? = null,
        entityId: String? = null,
        source: String? = null,
        category: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        changes: List<FieldChange> = emptyList(),
        failure: LogFailure? = null,
    ): LimitsEnforcement.Outcome = LimitsEnforcement.apply(
        limits,
        action,
        actor,
        entity,
        entityId,
        source,
        category,
        metadata,
        changes,
        failure,
    )

    private fun rejection(outcome: LimitsEnforcement.Outcome): String {
        assertFalse("expected the payload to be rejected", outcome.accepted)
        assertNull(outcome.actor)
        assertTrue(outcome.metadata.isEmpty())
        assertTrue(outcome.changes.isEmpty())
        assertNull(outcome.failure)
        return outcome.violation.orEmpty()
    }

    private fun nestedMaps(levels: Int): Any {
        var value: Any = 1
        repeat(levels) { index -> value = mapOf("k$index" to value) }
        return value
    }

    private fun nestedLists(levels: Int): Any {
        var value: Any = 1
        repeat(levels) { value = listOf(value) }
        return value
    }

    private fun nestedArrays(levels: Int): Any {
        var value: Any = 1
        repeat(levels) { value = arrayOf(value) }
        return value
    }

    private class Rendered(private val text: String) {
        override fun toString(): String = "Rendered($text)"
    }

    private class Explosive {
        override fun toString(): String = throw IllegalStateException("cannot render")
    }

    private fun containsMarker(value: Any?): Boolean = when (value) {
        is String -> value == EventLimits.TRUNCATION_MARKER
        is Map<*, *> -> value.values.any(::containsMarker)
        is Iterable<*> -> value.any(::containsMarker)
        is Array<*> -> value.any(::containsMarker)
        else -> false
    }

    // ------------------------------------------------------------------
    // REJECT
    // ------------------------------------------------------------------

    @Test
    fun `the action is checked before every other field`() {
        val violation = rejection(
            outcome(
                EventLimits(maxStringLength = 5),
                action = "abcdef",
                metadata = mapOf("opaque" to Any()),
            ),
        )
        assertEquals("action is 6 characters; the limit is 5", violation)
    }

    @Test
    fun `entity fields are each bounded`() {
        val limits = EventLimits(maxStringLength = 4)
        assertEquals("entity is 5 characters; the limit is 4", rejection(outcome(limits, entity = "abcde")))
        assertEquals("entityId is 5 characters; the limit is 4", rejection(outcome(limits, entityId = "abcde")))
        assertEquals("source is 5 characters; the limit is 4", rejection(outcome(limits, source = "abcde")))
        assertEquals("category is 5 characters; the limit is 4", rejection(outcome(limits, category = "abcde")))
    }

    @Test
    fun `actor id name and attributes are bounded`() {
        val limits = EventLimits(maxStringLength = 4, maxActorAttributes = 1)
        assertEquals(
            "actor.id is 5 characters; the limit is 4",
            rejection(outcome(limits, actor = Actor.user("abcde"))),
        )
        assertEquals(
            "actor.name is 5 characters; the limit is 4",
            rejection(outcome(limits, actor = Actor.user("u", name = "abcde"))),
        )
        assertEquals(
            "actor.attributes has 2 entries; the limit is 1",
            rejection(
                outcome(limits, actor = Actor.user("u", attributes = mapOf("a" to "1", "b" to "2"))),
            ),
        )
        assertEquals(
            "actor.attributes key is 5 characters; the limit is 4",
            rejection(outcome(limits, actor = Actor.user("u", attributes = mapOf("abcde" to "1")))),
        )
        assertEquals(
            "actor.attributes[k] is 5 characters; the limit is 4",
            rejection(outcome(limits, actor = Actor.user("u", attributes = mapOf("k" to "abcde")))),
        )
    }

    @Test
    fun `metadata and change entry counts are bounded`() {
        val limits = EventLimits(maxEntries = 2)
        assertEquals(
            "metadata has 3 entries; the limit is 2",
            rejection(outcome(limits, metadata = mapOf("a" to 1, "b" to 2, "c" to 3))),
        )
        assertEquals(
            "changes has 3 entries; the limit is 2",
            rejection(outcome(limits, changes = (1..3).map { FieldChange("f$it", 1, 2) })),
        )
    }

    @Test
    fun `metadata and change strings are bounded`() {
        val limits = EventLimits(maxStringLength = 4)
        assertEquals(
            "metadata key is 5 characters; the limit is 4",
            rejection(outcome(limits, metadata = mapOf("abcde" to "v"))),
        )
        assertEquals(
            "metadata[k] is 5 characters; the limit is 4",
            rejection(outcome(limits, metadata = mapOf("k" to "abcde"))),
        )
        assertEquals(
            "changes.field is 5 characters; the limit is 4",
            rejection(outcome(limits, changes = listOf(FieldChange("abcde", 1, 2)))),
        )
        assertEquals(
            "changes[k].before is 5 characters; the limit is 4",
            rejection(outcome(limits, changes = listOf(FieldChange("k", "abcde", 2)))),
        )
        assertEquals(
            "changes[k].after is 5 characters; the limit is 4",
            rejection(outcome(limits, changes = listOf(FieldChange("k", 1, "abcde")))),
        )
    }

    @Test
    fun `nested containers reject non-string keys oversized entries and excessive depth`() {
        val limits = EventLimits(maxEntries = 2)
        assertTrue(
            rejection(outcome(limits, metadata = mapOf("m" to mapOf(1 to "v"))))
                .contains("non-string key of type java.lang.Integer"),
        )
        assertTrue(
            rejection(outcome(limits, metadata = mapOf("m" to mapOf<Any?, Any?>(null to "v"))))
                .contains("non-string key of type null"),
        )
        assertTrue(
            rejection(outcome(limits, metadata = mapOf("a" to listOf(1, 2, 3))))
                .contains("metadata[a] has 3 entries; the limit is 2"),
        )
        assertTrue(
            rejection(outcome(limits, metadata = mapOf("a" to intArrayOf(1, 2, 3))))
                .contains("has 3 entries; the limit is 2"),
        )
        assertTrue(
            rejection(outcome(EventLimits(), metadata = mapOf("m" to nestedMaps(5))))
                .contains("nested deeper than 4 levels"),
        )
        assertTrue(
            rejection(outcome(EventLimits(), metadata = mapOf("l" to nestedLists(5))))
                .contains("nested deeper than 4 levels"),
        )
        assertTrue(
            rejection(outcome(EventLimits(), metadata = mapOf("a" to nestedArrays(5))))
                .contains("nested deeper than 4 levels"),
        )
    }

    @Test
    fun `unsupported values long enum names and cyclic structures are rejected`() {
        assertTrue(
            rejection(outcome(EventLimits(), metadata = mapOf("opaque" to Any())))
                .contains("which is not loggable"),
        )
        assertTrue(
            rejection(
                outcome(
                    EventLimits(maxStringLength = 4),
                    metadata = mapOf("e" to SampleEnum.A_VERY_LONG_CONSTANT),
                ),
            ).contains("(enum)"),
        )

        val cyclic = linkedMapOf<String, Any?>()
        cyclic["self"] = cyclic
        assertTrue(
            rejection(outcome(EventLimits(), metadata = mapOf("c" to cyclic)))
                .contains("nested deeper than 4 levels"),
        )
    }

    @Test
    fun `failure details and frames are bounded`() {
        val limits = EventLimits(maxStringLength = 8, maxFailureFrames = 2)
        assertEquals(
            "failure.errorType is 9 characters; the limit is 8",
            rejection(
                outcome(
                    limits,
                    failure = LogFailure("012345678", "m", null, emptyList()),
                ),
            ),
        )
        assertEquals(
            "failure.message is 9 characters; the limit is 8",
            rejection(
                outcome(
                    limits,
                    failure = LogFailure("E", "012345678", null, emptyList()),
                ),
            ),
        )
        assertEquals(
            "failure.rootCauseType is 9 characters; the limit is 8",
            rejection(
                outcome(
                    limits,
                    failure = LogFailure("E", "m", "012345678", emptyList()),
                ),
            ),
        )
        assertEquals(
            "failure.stackSummary has 3 frames; the limit is 2",
            rejection(
                outcome(
                    limits,
                    failure = LogFailure("E", "m", null, listOf("a", "b", "c")),
                ),
            ),
        )
        assertEquals(
            "failure.stackSummary[0] is 9 characters; the limit is 8",
            rejection(
                outcome(
                    limits,
                    failure = LogFailure("E", "m", null, listOf("012345678")),
                ),
            ),
        )
    }

    @Test
    fun `the byte budget bounds otherwise valid payloads`() {
        val violation = rejection(
            outcome(
                EventLimits(maxEventBytes = 11),
                action = "a",
                metadata = mapOf("k" to "0123456789"),
            ),
        )
        assertEquals("event payload exceeds the limit of 11 bytes", violation)
    }

    @Test
    fun `the expanded safe model and primitive arrays are accepted`() {
        val metadata = mapOf(
            "null" to null,
            "char" to 'c',
            "boolean" to true,
            "int" to 1,
            "uuid" to UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            "instant" to Instant.parse("2026-01-01T00:00:00Z"),
            "duration" to Duration.ofHours(2),
            "decimal" to BigDecimal("19.99"),
            "big" to BigInteger.TEN,
            "uint" to 42u,
            "ulong" to 7uL,
            "safe" to LogSafeValue.of(Any()),
            "ints" to intArrayOf(1, 2),
            "longs" to longArrayOf(1, 2),
            "shorts" to shortArrayOf(1, 2),
            "bytes" to byteArrayOf(1, 2),
            "floats" to floatArrayOf(1f, 2f),
            "doubles" to doubleArrayOf(1.0, 2.0),
            "booleans" to booleanArrayOf(true, false),
            "chars" to charArrayOf('a', 'b'),
            "uints" to uintArrayOf(1u, 2u),
            "ulongs" to ulongArrayOf(1uL, 2uL),
            "ushorts" to ushortArrayOf(1u.toUShort(), 2u.toUShort()),
            "ubytes" to ubyteArrayOf(1u.toUByte(), 2u.toUByte()),
        )
        val result = outcome(EventLimits(), metadata = metadata)
        assertTrue(result.accepted)
        assertFalse(result.truncated)
        assertSame(metadata, result.metadata)
    }

    @Test
    fun `an unbounded lazy collection terminates at the entry boundary`() {
        val infinite = Iterable { generateSequence(0) { it + 1 }.iterator() }
        val violation = rejection(
            outcome(EventLimits(maxEntries = 2), metadata = mapOf("n" to infinite)),
        )
        assertTrue(violation.contains("has 3 entries; the limit is 2"))
    }

    // ------------------------------------------------------------------
    // TRUNCATE
    // ------------------------------------------------------------------

    @Test
    fun `entity fields are shortened and marked`() {
        val limits = EventLimits(maxStringLength = 20, action = LimitAction.TRUNCATE)
        val result = outcome(
            limits,
            entity = "e".repeat(30),
            entityId = "i".repeat(30),
            source = "s".repeat(30),
            category = "c".repeat(30),
        )
        assertTrue(result.accepted)
        assertTrue(result.truncated)
        for (field in listOf(result.entity, result.entityId, result.source, result.category)) {
            assertEquals(20, field!!.length)
            assertTrue(field.endsWith(EventLimits.TRUNCATION_MARKER))
        }
        assertEquals(true, result.metadata[EventLimits.TRUNCATED_KEY])
    }

    @Test
    fun `actor fields and attributes are shortened and bounded`() {
        val limits = EventLimits(maxStringLength = 20, maxActorAttributes = 1, action = LimitAction.TRUNCATE)
        val actor = Actor.user(
            "u".repeat(30),
            name = "n".repeat(30),
            attributes = linkedMapOf("a" to "1".repeat(30), "b" to "2"),
        )
        val result = outcome(limits, actor = actor)
        assertTrue(result.accepted)
        assertTrue(result.truncated)
        val limited = result.actor!!
        assertEquals(1, limited.attributes.size)
        assertEquals(20, limited.id!!.length)
        assertEquals(20, limited.name!!.length)
        assertEquals(20, limited.attributes.getValue("a").length)
        assertTrue(limited.attributes.getValue("a").endsWith(EventLimits.TRUNCATION_MARKER))

        val none = outcome(
            EventLimits(maxActorAttributes = 0, action = LimitAction.TRUNCATE),
            actor = actor,
        )
        assertTrue(none.truncated)
        assertTrue(none.actor!!.attributes.isEmpty())
    }

    @Test
    fun `changes are shortened and capped`() {
        val limits = EventLimits(maxStringLength = 20, maxEntries = 2, action = LimitAction.TRUNCATE)
        val changes = listOf(
            FieldChange("f".repeat(30), "b".repeat(30), "a".repeat(30)),
            FieldChange("second", "x", "y"),
            FieldChange("third", "x", "y"),
        )
        val result = outcome(limits, changes = changes)
        assertTrue(result.accepted)
        assertTrue(result.truncated)
        assertEquals(2, result.changes.size)
        assertEquals(20, result.changes[0].field.length)
        assertTrue(result.changes[0].field.endsWith(EventLimits.TRUNCATION_MARKER))
        assertEquals(20, (result.changes[0].before as String).length)
        assertEquals(20, (result.changes[0].after as String).length)
        assertEquals("second", result.changes[1].field)
    }

    @Test
    fun `failure details and frames are shortened`() {
        val failure = LogFailure(
            errorType = "t".repeat(30),
            message = "m".repeat(30),
            rootCauseType = "r".repeat(30),
            stackSummary = listOf("f".repeat(30), "g", "h"),
            cancelled = true,
        )
        val limits = EventLimits(maxStringLength = 20, maxFailureFrames = 2, action = LimitAction.TRUNCATE)
        val result = outcome(limits, failure = failure)
        val limited = result.failure!!
        assertEquals(20, limited.errorType.length)
        assertEquals(20, limited.message!!.length)
        assertEquals(20, limited.rootCauseType!!.length)
        assertEquals(2, limited.stackSummary.size)
        assertEquals(20, limited.stackSummary[0].length)
        assertTrue(limited.stackSummary[0].endsWith(EventLimits.TRUNCATION_MARKER))
        assertTrue(limited.cancelled)
        assertTrue(result.truncated)
    }

    @Test
    fun `non-string map keys and unsupported values are rendered as strings`() {
        val nested = linkedMapOf<Any?, Any?>(1 to "one", null to "nil")
        val result = outcome(
            EventLimits(action = LimitAction.TRUNCATE),
            metadata = mapOf("m" to nested, "rendered" to Rendered("ok")),
        )
        assertTrue(result.accepted)
        assertTrue(result.truncated)
        val map = result.metadata["m"] as Map<*, *>
        assertEquals("one", map["1"])
        assertEquals("nil", map["null"])
        assertEquals("Rendered(ok)", result.metadata["rendered"])
    }

    @Test
    fun `a value whose toString throws is replaced without failing the event`() {
        val result = outcome(
            EventLimits(action = LimitAction.TRUNCATE),
            metadata = mapOf("x" to Explosive()),
        )
        assertTrue(result.accepted)
        assertTrue(result.truncated)
        val stored = result.metadata["x"] as String
        assertTrue(stored.startsWith("<unrenderable "))
        assertTrue(stored.contains("Explosive"))
    }

    @Test
    fun `deeply nested maps lists and arrays become the depth marker`() {
        val result = outcome(
            EventLimits(action = LimitAction.TRUNCATE),
            metadata = mapOf(
                "map" to nestedMaps(6),
                "list" to nestedLists(6),
                "array" to nestedArrays(6),
            ),
        )
        assertTrue(result.accepted)
        assertTrue(result.truncated)
        assertTrue(containsMarker(result.metadata["map"]))
        assertTrue(containsMarker(result.metadata["list"]))
        assertTrue(containsMarker(result.metadata["array"]))
    }

    @Test
    fun `marking a truncated event keeps room within the entry limit`() {
        val limits = EventLimits(maxStringLength = 5, maxEntries = 3, action = LimitAction.TRUNCATE)
        val result = outcome(
            limits,
            metadata = linkedMapOf("a" to "1", "b" to "x".repeat(10), "c" to "3"),
        )
        assertTrue(result.accepted)
        assertTrue(result.truncated)
        assertEquals(listOf("a", "b", EventLimits.TRUNCATED_KEY), result.metadata.keys.toList())
        assertEquals(true, result.metadata[EventLimits.TRUNCATED_KEY])
    }

    @Test
    fun `a string limit below the marker length shortens the marker itself`() {
        val result = outcome(
            EventLimits(maxStringLength = 5, action = LimitAction.TRUNCATE),
            metadata = mapOf("k" to "x".repeat(20)),
        )
        assertEquals(EventLimits.TRUNCATION_MARKER.take(5), result.metadata["k"])
    }

    @Test
    fun `an exhausted byte budget rejects even after truncation`() {
        val result = outcome(
            EventLimits(maxEventBytes = 8, maxStringLength = 5, action = LimitAction.TRUNCATE),
            metadata = mapOf("key" to "value"),
        )
        assertFalse(result.accepted)
        assertTrue(result.violation!!.contains("even after truncation"))
    }

    @Test
    fun `primitive arrays are capped and characters become strings`() {
        val limits = EventLimits(maxEntries = 3, action = LimitAction.TRUNCATE)

        fun stored(value: Any): Any? = outcome(limits, metadata = mapOf("v" to value)).metadata["v"]

        assertEquals(listOf(1, 2, 3), stored(intArrayOf(1, 2, 3, 4, 5)))
        assertEquals(listOf(1L, 2L, 3L), stored(longArrayOf(1, 2, 3, 4)))
        assertEquals(
            listOf(1.toShort(), 2.toShort(), 3.toShort()),
            stored(shortArrayOf(1, 2, 3, 4)),
        )
        assertEquals(listOf<Byte>(1, 2, 3), stored(byteArrayOf(1, 2, 3, 4)))
        assertEquals(listOf(1f, 2f, 3f), stored(floatArrayOf(1f, 2f, 3f, 4f)))
        assertEquals(listOf(1.0, 2.0, 3.0), stored(doubleArrayOf(1.0, 2.0, 3.0, 4.0)))
        assertEquals(
            listOf(true, false, true),
            stored(booleanArrayOf(true, false, true, false)),
        )
        assertEquals(listOf("a", "b", "c"), stored(charArrayOf('a', 'b', 'c')))
        assertEquals(listOf(1u, 2u, 3u), stored(uintArrayOf(1u, 2u, 3u, 4u)))
        assertEquals(listOf(1uL, 2uL, 3uL), stored(ulongArrayOf(1uL, 2uL, 3uL, 4uL)))
        assertEquals(
            listOf(1u.toUShort(), 2u.toUShort(), 3u.toUShort()),
            stored(
                ushortArrayOf(
                    1u.toUShort(),
                    2u.toUShort(),
                    3u.toUShort(),
                    4u.toUShort(),
                ),
            ),
        )
        assertEquals(
            listOf(1u.toUByte(), 2u.toUByte(), 3u.toUByte()),
            stored(
                ubyteArrayOf(
                    1u.toUByte(),
                    2u.toUByte(),
                    3u.toUByte(),
                    4u.toUByte(),
                ),
            ),
        )
    }

    @Test
    fun `the engine delivers truncated fields and counts the event once`() {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            dispatch = io.ilogger.annotations.DispatchMode.INLINE
            limits = EventLimits(maxStringLength = 20, action = LimitAction.TRUNCATE)
        }
        logger.use {
            it.recordNow(
                action = "task.created",
                entity = "e".repeat(30),
                dispatch = io.ilogger.annotations.DispatchMode.INLINE,
            )
        }
        val event = sink.events.single()
        assertEquals(20, event.entity!!.length)
        assertTrue(event.entity!!.endsWith(EventLimits.TRUNCATION_MARKER))
        assertEquals(true, event.metadata[EventLimits.TRUNCATED_KEY])
        assertEquals(1L, logger.stats().truncated)
    }

    private enum class SampleEnum {
        A_VERY_LONG_CONSTANT,
    }
}
