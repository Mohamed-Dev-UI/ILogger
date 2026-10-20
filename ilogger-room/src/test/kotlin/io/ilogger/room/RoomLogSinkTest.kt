package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Contract tests for [RoomLogSink]'s batching semantics against a fake DAO
 * write (the mapping/insertion itself lives in the application's schema —
 * see `examples/room` for a real Room wiring).
 */
class RoomLogSinkTest {

    private fun event(id: String): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.AUDIT,
        action = "task.created",
    )

    @Test
    fun `single write produces a one-element batch`() = runBlocking {
        val batches = mutableListOf<List<LogEvent>>()
        val sink = RoomLogSink { batches.add(it) }

        sink.write(event("e-1"))

        assertEquals(1, batches.size)
        assertEquals(listOf("e-1"), batches.single().map { it.id })
    }

    @Test
    fun `writeAll forwards the whole batch in one call`() = runBlocking {
        val batches = mutableListOf<List<LogEvent>>()
        val sink = RoomLogSink { batches.add(it) }

        sink.writeAll(listOf(event("e-1"), event("e-2"), event("e-3")))

        assertEquals(1, batches.size)
        assertEquals(listOf("e-1", "e-2", "e-3"), batches.single().map { it.id })
    }

    @Test
    fun `empty writeAll does not call the dao`() = runBlocking {
        var calls = 0
        val sink = RoomLogSink { calls++ }

        sink.writeAll(emptyList())

        assertEquals(0, calls)
    }

    @Test
    fun `insert failure propagates to the engine error mode`() = runBlocking {
        val sink = RoomLogSink { throw IllegalStateException("disk full") }

        try {
            sink.write(event("e-1"))
            fail("expected the dao failure to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("disk full", expected.message)
        }
    }

    @Test
    fun `close is a no-op`() {
        val sink = RoomLogSink { fail("close must not write") }
        sink.close()
        assertTrue(true)
    }
}
