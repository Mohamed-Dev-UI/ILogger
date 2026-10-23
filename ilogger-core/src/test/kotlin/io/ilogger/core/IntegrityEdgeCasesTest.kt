@file:OptIn(ExperimentalUnsignedTypes::class)

package io.ilogger.core

import io.ilogger.annotations.LogLevel
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityEdgeCasesTest {

    private val key = SigningKey("k1", HmacTrailSigner("edge-secret".toByteArray()))

    private fun event(action: String, metadata: Map<String, Any?> = emptyMap()): LogEvent = LogEvent(
        id = "evt-$action",
        timestampMillis = 1_700_000_000_000L,
        level = LogLevel.AUDIT,
        action = action,
        metadata = metadata,
    )

    private fun stamped(vararg actions: String): List<LogEvent> = runBlocking {
        val sink = RecordingSink()
        val chained = HashChainSink(sink, key)
        actions.forEach { chained.write(event(it)) }
        chained.close()
        sink.events.toList()
    }

    private fun input(event: LogEvent): ByteArray =
        TrailVerifier.chainInput(event, 0, HashChainSink.GENESIS_BYTES)

    private class LifecycleSink : LogSink {
        var flushed = 0
        var closed = false

        override suspend fun write(event: LogEvent): Unit = Unit

        override suspend fun flush() {
            flushed++
        }

        override fun close() {
            closed = true
        }
    }

    private class Rendered(private val text: String) {
        override fun toString(): String = "Rendered($text)"
    }

    private class Explosive {
        override fun toString(): String = throw IllegalStateException("cannot render")
    }

    @Test
    fun `trail signer verify accepts matching stamps and rejects others`() {
        val signer = HmacTrailSigner("signing".toByteArray())
        val payload = "payload".toByteArray()
        val stamp = signer.sign(payload)

        assertTrue(signer.verify(payload, stamp))
        assertFalse(signer.verify("other".toByteArray(), stamp))
        assertFalse(signer.verify(payload, ByteArray(stamp.size)))
    }

    @Test
    fun `hash chain sink forwards flush and close`() = runBlocking {
        val delegate = LifecycleSink()
        val chained = HashChainSink(delegate, key)
        chained.write(event("a"))
        chained.flush()
        chained.close()

        assertEquals(1, delegate.flushed)
        assertTrue(delegate.closed)
    }

    @Test
    fun `an empty list verifies as valid`() {
        val result = TrailVerifier.verify(emptyList())
        assertTrue(result.valid)
        assertEquals(0, result.checked)
    }

    @Test
    fun `each missing stamp is reported by name`() {
        val events = stamped("a")
        for (stamp in listOf(TrailKeys.SEQ, TrailKeys.PREV, TrailKeys.HASH, TrailKeys.KEY_ID)) {
            val stripped = listOf(events[0].copy(metadata = events[0].metadata - stamp))
            val result = TrailVerifier.verify(stripped)
            assertFalse("stamp $stamp must be required", result.valid)
            assertTrue(result.reason.orEmpty().contains(stamp))
        }
    }

    @Test
    fun `a malformed base64 stamp is treated as missing`() {
        val events = stamped("a")
        val tampered = listOf(
            events[0].copy(
                metadata = events[0].metadata + (TrailKeys.HASH to "not-base64!!"),
            ),
        )
        val result = TrailVerifier.verify(tampered)
        assertFalse(result.valid)
        assertTrue(result.reason.orEmpty().contains(TrailKeys.HASH))
    }

    @Test
    fun `a continuation without its segment start is rejected`() {
        val events = stamped("a", "b")
        // Sequence 0 that is not chained to the genesis stamp: the record
        // claims to continue a chain whose earlier stamps are absent.
        val orphan = events[1].copy(
            metadata = events[1].metadata + (TrailKeys.SEQ to 0L),
        )
        val result = TrailVerifier.verify(listOf(orphan))
        assertFalse(result.valid)
        assertTrue(
            result.reason.orEmpty().contains("start is not in the verified list"),
        )
    }

    @Test
    fun `a previous stamp that does not link is rejected`() {
        val events = stamped("a", "b")
        val tampered = listOf(
            events[0],
            events[1].copy(
                metadata = events[1].metadata + (TrailKeys.PREV to HashChainSink.GENESIS_STAMP),
            ),
        )
        val result = TrailVerifier.verify(tampered)
        assertFalse(result.valid)
        assertEquals(1, result.brokenAt)
        assertTrue(result.reason.orEmpty().contains("does not link"))
    }

    @Test
    fun `a sequence number that skips ahead is rejected`() {
        val events = stamped("a", "b")
        val tampered = listOf(
            events[0],
            events[1].copy(metadata = events[1].metadata + (TrailKeys.SEQ to 5L)),
        )
        val result = TrailVerifier.verify(tampered)
        assertFalse(result.valid)
        assertTrue(result.reason.orEmpty().contains("sequence 5 does not follow 0"))
    }

    @Test
    fun `segment restarts are accepted anywhere in the list`() = runBlocking {
        val first = stamped("a", "b")
        val second = stamped("c", "d")
        val combined = first + second

        val structural = TrailVerifier.verify(combined)
        assertTrue(structural.reason, structural.valid)

        val keyed = TrailVerifier.verify(combined, mapOf("k1" to key.signer))
        assertTrue(keyed.reason, keyed.valid)
    }

    @Test
    fun `keyed verification reports an unknown key id`() {
        val events = stamped("a")
        val result = TrailVerifier.verify(
            events,
            mapOf("other" to HmacTrailSigner("other".toByteArray())),
        )
        assertFalse(result.valid)
        assertTrue(result.reason.orEmpty().contains("no key for id 'k1'"))
    }

    @Test
    fun `the chain replaces reserved metadata supplied by the caller`() = runBlocking {
        val sink = RecordingSink()
        val chained = HashChainSink(sink, key)
        chained.write(
            event(
                "a",
                metadata = mapOf(
                    TrailKeys.SEQ to 99L,
                    TrailKeys.PREV to "forged",
                    TrailKeys.HASH to "forged",
                    TrailKeys.KEY_ID to "forged",
                ),
            ),
        )
        chained.close()

        val stored = sink.events.single()
        assertEquals(0L, stored.metadata[TrailKeys.SEQ])
        assertNotEquals("forged", stored.metadata[TrailKeys.HASH])
        assertEquals("k1", stored.metadata[TrailKeys.KEY_ID])
        assertTrue(TrailVerifier.verify(listOf(stored), mapOf("k1" to key.signer)).valid)
    }

    @Test
    fun `canonical input renders every supported value family deterministically`() {
        val metadata = mapOf(
            "null" to null,
            "string" to "text",
            "boolean" to true,
            "char" to 'c',
            "int" to 1,
            "long" to 2L,
            "double" to 2.5,
            "uint" to 1u,
            "ulong" to 2uL,
            "uuid" to UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            "instant" to Instant.parse("2026-01-01T00:00:00Z"),
            "duration" to Duration.ofSeconds(5),
            "enum" to LogLevel.INFO,
            "map" to mapOf("k" to 1),
            "iterable" to listOf(1, 2),
            "array" to arrayOf(1, 2),
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
            "rendered" to Rendered("ok"),
            "explosive" to Explosive(),
        )
        val first = input(event("canonical", metadata))
        val second = input(event("canonical", metadata))
        assertTrue(first.contentEquals(second))

        val text = String(first, Charsets.UTF_8)
        assertTrue(text.contains("Rendered(ok)"))
        assertTrue(text.contains("Explosive"))
    }

    @Test
    fun `canonical input bounds nested maps lists and arrays`() {
        val text = String(
            input(
                event(
                    "deep",
                    metadata = mapOf(
                        "map" to mapOf("a" to mapOf("b" to mapOf("c" to 1))),
                        "list" to listOf(listOf(listOf(1))),
                        "array" to arrayOf(arrayOf(arrayOf(1))),
                    ),
                ),
            ),
            Charsets.UTF_8,
        )
        assertTrue(text.contains("<max-depth>"))
    }

    @Test
    fun `changes are part of the canonical input`() {
        val first = event("a").copy(
            changes = listOf(FieldChange("f", 1, 2), FieldChange("g", 3, 4)),
        )
        val same = event("a").copy(
            changes = listOf(FieldChange("f", 1, 2), FieldChange("g", 3, 4)),
        )
        val reordered = event("a").copy(
            changes = listOf(FieldChange("g", 3, 4), FieldChange("f", 1, 2)),
        )
        assertTrue(input(first).contentEquals(input(same)))
        assertFalse(input(first).contentEquals(input(reordered)))
    }
}
