package io.ilogger.otel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class W3cTraceParentTest {

    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val spanId = "00f067aa0ba902b7"

    @Test
    fun `parses and formats a sampled header`() {
        val parent = W3cTraceParent.parse("00-$traceId-$spanId-01")
        assertNotNull(parent)
        parent!!
        assertEquals(traceId, parent.traceId)
        assertEquals(spanId, parent.spanId)
        assertTrue(parent.sampled)
        assertEquals("00-$traceId-$spanId-01", parent.format())
    }

    @Test
    fun `unsampled flags round trip`() {
        val parent = W3cTraceParent.parse("00-$traceId-$spanId-00")!!
        assertFalse(parent.sampled)
        assertEquals("00-$traceId-$spanId-00", parent.format())
    }

    @Test
    fun `other flag bits do not affect sampled`() {
        assertFalse(W3cTraceParent.parse("00-$traceId-$spanId-02")!!.sampled)
        assertTrue(W3cTraceParent.parse("00-$traceId-$spanId-03")!!.sampled)
    }

    @Test
    fun `accepts future versions with trailing fields`() {
        val parent = W3cTraceParent.parse("01-$traceId-$spanId-01-whats-next")
        assertNotNull(parent)
        assertEquals(traceId, parent!!.traceId)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertNotNull(W3cTraceParent.parse("  00-$traceId-$spanId-01  "))
    }

    @Test
    fun `rejects malformed headers`() {
        val invalid = listOf(
            null,
            "",
            "00-$traceId-$spanId",
            "00-$traceId-$spanId-01-extra",
            "ff-$traceId-$spanId-01",
            "0-$traceId-$spanId-01",
            "zz-$traceId-$spanId-01",
            "00-${traceId.uppercase()}-$spanId-01",
            "00-${"0".repeat(32)}-$spanId-01",
            "00-$traceId-${"0".repeat(16)}-01",
            "00-$traceId-00f067aa0ba902b-01",
            "00-${traceId}0-$spanId-01",
            "00-$traceId-${spanId}0-01",
            "00-$traceId-$spanId-1",
            "00-$traceId-$spanId-zz",
        )
        for (header in invalid) {
            assertNull("expected null for <$header>", W3cTraceParent.parse(header))
        }
    }

    @Test
    fun `rejects invalid ids at construction`() {
        val cases = listOf<() -> Unit>(
            { W3cTraceParent(traceId = "0".repeat(32), spanId = spanId) },
            { W3cTraceParent(traceId = traceId, spanId = "0".repeat(16)) },
            { W3cTraceParent(traceId = traceId.uppercase(), spanId = spanId) },
            { W3cTraceParent(traceId = traceId.dropLast(1), spanId = spanId) },
        )
        for (case in cases) {
            assertThrows<IllegalArgumentException>(case)
        }
    }

    @Test
    fun `toSpanContext is a remote parent`() {
        val context = W3cTraceParent(traceId, spanId, sampled = true).toSpanContext()
        assertTrue(context.isValid)
        assertTrue(context.isRemote)
        assertEquals(traceId, context.traceId)
        assertEquals(spanId, context.spanId)
        assertTrue(context.traceFlags.isSampled)
    }

    @Test
    fun `context attributes carry the header`() {
        val parent = W3cTraceParent(traceId, spanId, sampled = false)
        assertEquals(
            mapOf("traceparent" to "00-$traceId-$spanId-00"),
            parent.toContextAttributes(),
        )
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("expected ${T::class.java.name}, nothing was thrown")
    }
}
