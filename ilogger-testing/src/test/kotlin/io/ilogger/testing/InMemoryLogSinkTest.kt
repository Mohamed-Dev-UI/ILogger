package io.ilogger.testing

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the published sink contract against the in-memory fixture. */
class InMemoryLogSinkConformanceTest : LogSinkConformance() {

    override fun createSink(): LogSink = InMemoryLogSink()

    override fun deliveredIds(sink: LogSink): List<String> =
        (sink as InMemoryLogSink).events().map { it.id }

    override fun deliveredEvents(sink: LogSink): List<LogEvent> =
        (sink as InMemoryLogSink).events()
}

class InMemoryLogSinkTest {

    private fun event(id: String): LogEvent =
        LogEvent(id = id, timestampMillis = 1_000, level = LogLevel.AUDIT, action = "memory.$id")

    @Test
    fun `clear forgets events and keeps the sink usable`() = runBlocking {
        val sink = InMemoryLogSink()
        assertNull(sink.last())
        assertEquals(0, sink.count())

        sink.write(event("a"))
        sink.writeAll(listOf(event("b"), event("c")))
        assertEquals("c", sink.last()!!.id)
        assertEquals(3, sink.count())

        sink.clear()

        assertEquals(0, sink.count())
        assertNull(sink.last())
        sink.write(event("d"))
        assertEquals(listOf("d"), sink.events().map { it.id })
    }

    @Test
    fun `awaitCount returns immediately once enough events arrived`() = runBlocking {
        val sink = InMemoryLogSink()
        sink.write(event("a"))
        sink.write(event("b"))

        assertTrue(sink.awaitCount(0))
        assertTrue(sink.awaitCount(2))
    }

    @Test
    fun `awaitCount reports a timeout when not enough events arrived`() = runBlocking {
        val sink = InMemoryLogSink()
        sink.write(event("a"))

        assertFalse(sink.awaitCount(2, timeoutMillis = 10))
        assertTrue(sink.awaitCount(1, timeoutMillis = 10))
    }
}
