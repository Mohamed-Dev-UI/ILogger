package io.ilogger.core

import io.ilogger.annotations.LogLevel
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityTest {

    private val key1 = SigningKey("k1", HmacTrailSigner("key-one".toByteArray()))
    private val key2 = SigningKey("k2", HmacTrailSigner("key-two".toByteArray()))
    private val verifierKeys = mapOf("k1" to key1.signer, "k2" to key2.signer)

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

    private suspend fun chain(
        vararg events: LogEvent,
        keys: List<SigningKey> = listOf(key1),
    ): RecordingSink {
        val sink = RecordingSink()
        val current = AtomicReference(keys.first())
        val chained = HashChainSink(sink) { current.get() }
        events.forEach { chained.write(it) }
        chained.close()
        return sink
    }

    @Test
    fun `genesis stamp is base64url of 32 zero bytes`() {
        assertEquals(32, HashChainSink.GENESIS_BYTES.size)
        assertEquals("A".repeat(43), HashChainSink.GENESIS_STAMP)
    }

    @Test
    fun `every event is stamped with sequence, previous stamp, hash and key id`() = runBlocking {
        val sink = chain(event("task.created"), event("task.updated"))

        val first = sink.events[0]
        assertEquals(0L, first.metadata[TrailKeys.SEQ])
        assertEquals(HashChainSink.GENESIS_STAMP, first.metadata[TrailKeys.PREV])
        assertNotNull(first.metadata[TrailKeys.HASH])
        assertEquals("k1", first.metadata[TrailKeys.KEY_ID])

        val second = sink.events[1]
        assertEquals(1L, second.metadata[TrailKeys.SEQ])
        assertEquals(first.metadata[TrailKeys.HASH], second.metadata[TrailKeys.PREV])
    }

    @Test
    fun `verification accepts an unmodified chain`() = runBlocking {
        val sink = chain(event("a"), event("b"), event("c"), event("d"))
        val result = TrailVerifier.verify(sink.events, verifierKeys)
        assertTrue(result.reason, result.valid)
        assertEquals(4, result.checked)
        assertNull(result.brokenAt)
        assertNull(result.reason)
    }

    @Test
    fun `modifying an action breaks verification at its index`() = runBlocking {
        val sink = chain(event("a"), event("b"), event("c"))
        val tampered = sink.events.toMutableList()
        tampered[1] = tampered[1].copy(action = "forged.action")

        val result = TrailVerifier.verify(tampered, verifierKeys)
        assertFalse(result.valid)
        assertEquals(1, result.brokenAt)
        assertEquals(2, result.checked)
        assertTrue(result.reason!!.contains("does not match"))
    }

    @Test
    fun `changing metadata breaks verification`() = runBlocking {
        val sink = chain(event("payment.charged", metadata = mapOf("amount" to 10L)))
        val tampered = sink.events.toMutableList()
        tampered[0] = tampered[0].copy(metadata = tampered[0].metadata + ("amount" to 1_000_000L))

        val result = TrailVerifier.verify(tampered, verifierKeys)
        assertFalse(result.valid)
        assertEquals(0, result.brokenAt)
    }

    @Test
    fun `reordering records breaks verification`() = runBlocking {
        val sink = chain(event("a"), event("b"), event("c"), event("d"))
        val reordered = sink.events.toMutableList()
        val moved = reordered[1]
        reordered[1] = reordered[2]
        reordered[2] = moved

        val result = TrailVerifier.verify(reordered, verifierKeys)
        assertFalse(result.valid)
        assertEquals(1, result.brokenAt)
    }

    @Test
    fun `deleting a record inside a segment breaks verification`() = runBlocking {
        val sink = chain(event("a"), event("b"), event("c"), event("d"))
        val deleted = sink.events.toMutableList().also { it.removeAt(2) }

        val result = TrailVerifier.verify(deleted, verifierKeys)
        assertFalse(result.valid)
        assertEquals(2, result.brokenAt)
    }

    @Test
    fun `inserting a forged record breaks verification`() = runBlocking {
        val sink = chain(event("a"), event("b"), event("c"))
        val forged = sink.events.toMutableList()
        forged.add(
            2,
            event("injected").copy(
                metadata = buildMap {
                    put(TrailKeys.SEQ, 2L)
                    put(TrailKeys.PREV, sink.events[1].metadata[TrailKeys.HASH])
                    put(TrailKeys.HASH, "A".repeat(43))
                    put(TrailKeys.KEY_ID, "k1")
                },
            ),
        )

        val result = TrailVerifier.verify(forged, verifierKeys)
        assertFalse(result.valid)
        assertEquals(2, result.brokenAt)
        assertTrue(result.reason!!.contains("does not match"))
    }

    @Test
    fun `structural verification checks links but not record content`() = runBlocking {
        val sink = chain(event("a"), event("b"), event("c"))
        val tampered = sink.events.toMutableList()
        tampered[1] = tampered[1].copy(action = "forged.action")

        // Without keys a stamp cannot be recomputed, so content tampering is
        // not detectable — this pins the documented limitation.
        val structural = TrailVerifier.verify(tampered)
        assertTrue(structural.reason, structural.valid)

        val keyed = TrailVerifier.verify(tampered, verifierKeys)
        assertFalse(keyed.valid)
    }

    @Test
    fun `structural verification rejects unstamped records`() {
        val result = TrailVerifier.verify(listOf(event("plain")))
        assertFalse(result.valid)
        assertEquals(0, result.brokenAt)
        assertTrue(result.reason!!.contains(TrailKeys.SEQ))
    }

    @Test
    fun `key rotation mid-chain verifies with old and new keys`() = runBlocking {
        val sink = RecordingSink()
        val current = AtomicReference(key1)
        val chained = HashChainSink(sink) { current.get() }
        chained.write(event("a"))
        chained.write(event("b"))
        current.set(key2)
        chained.write(event("c"))
        chained.write(event("d"))
        chained.close()

        assertEquals("k1", sink.events[1].metadata[TrailKeys.KEY_ID])
        assertEquals("k2", sink.events[2].metadata[TrailKeys.KEY_ID])
        assertEquals(sink.events[1].metadata[TrailKeys.HASH], sink.events[2].metadata[TrailKeys.PREV])

        val both = TrailVerifier.verify(sink.events, verifierKeys)
        assertTrue(both.reason, both.valid)

        val oldOnly = TrailVerifier.verify(sink.events, mapOf("k1" to key1.signer))
        assertFalse(oldOnly.valid)
        assertEquals(2, oldOnly.brokenAt)
        assertTrue(oldOnly.reason!!.contains("no key"))
    }

    @Test
    fun `concurrent writes keep the chain consistent`() = runBlocking {
        val sink = RecordingSink()
        val chained = HashChainSink(sink, key1)
        (1..8).map { thread ->
            async(Dispatchers.IO) {
                repeat(25) { n -> chained.write(event("task.$thread.$n")) }
            }
        }.awaitAll()
        chained.close()

        assertEquals(200, sink.events.size)
        val result = TrailVerifier.verify(sink.events, verifierKeys)
        assertTrue(result.reason, result.valid)
    }

    @Test
    fun `writeAll keeps the chain order across a batch`() = runBlocking {
        val sink = RecordingSink()
        val chained = HashChainSink(sink, key1)
        chained.writeAll(listOf(event("a"), event("b"), event("c")))
        chained.close()

        assertEquals(3, sink.events.size)
        val result = TrailVerifier.verify(sink.events, verifierKeys)
        assertTrue(result.reason, result.valid)
    }

    @Test
    fun `chain input excludes reserved stamps and is metadata-order independent`() {
        val first = event("a", metadata = linkedMapOf("z" to 1L, "a" to 2L))
        val second = event("a", metadata = linkedMapOf("a" to 2L, "z" to 1L, "ilogger.custom" to "x"))

        val left = TrailVerifier.chainInput(first, 0, HashChainSink.GENESIS_BYTES)
        val right = TrailVerifier.chainInput(second, 0, HashChainSink.GENESIS_BYTES)
        assertTrue(left.contentEquals(right))
    }

    @Test
    fun `deeply nested metadata is canonically bounded like the JSON codec`() {
        val deep = event("a", metadata = mapOf("m" to mapOf("n" to mapOf("o" to mapOf("p" to 1L)))))
        val bounded = event("a", metadata = mapOf("m" to mapOf("n" to mapOf("o" to "<max-depth>"))))

        val left = TrailVerifier.chainInput(deep, 0, HashChainSink.GENESIS_BYTES)
        val right = TrailVerifier.chainInput(bounded, 0, HashChainSink.GENESIS_BYTES)
        assertTrue(left.contentEquals(right))
    }
}
