package io.ilogger.testing

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.sink.EventCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the published sink contract against the scriptable fake. */
class FakeLogSinkConformanceTest : LogSinkConformance() {

    override fun createSink(): LogSink = FakeLogSink()

    override fun deliveredIds(sink: LogSink): List<String> =
        (sink as FakeLogSink).events().map { it.id }

    override fun deliveredEvents(sink: LogSink): List<LogEvent> =
        (sink as FakeLogSink).events()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FakeLogSinkTest {

    private fun event(id: String): LogEvent =
        LogEvent(id = id, timestampMillis = 1_000, level = LogLevel.AUDIT, action = "fake.$id")

    @Test
    fun `records events batches and payloads`() = runBlocking {
        val sink = FakeLogSink(codec = EventCodec { "id=${it.id}" })

        sink.write(event("a"))
        sink.writeAll(listOf(event("b"), event("c")))

        assertEquals(listOf("a", "b", "c"), sink.events().map { it.id })
        assertEquals(listOf(listOf("b", "c")), sink.batches().map { batch -> batch.map { it.id } })
        assertEquals(listOf("id=a", "id=b", "id=c"), sink.payloads())
        assertEquals(3, sink.writtenEventCount())
        assertEquals(3, sink.attemptedEventCount())
        assertEquals(2, sink.writeCallCount())
        assertEquals(1, sink.writeAllCallCount())
        assertEquals("c", sink.last()!!.id)
    }

    @Test
    fun `failNextWrites fails exactly the configured calls`() = runBlocking {
        val sink = FakeLogSink().failNextWrites(2)

        val outcomes = (1..3).map { index -> runCatching { sink.write(event("e$index")) } }

        assertTrue(outcomes[0].isFailure)
        assertTrue(outcomes[1].isFailure)
        assertTrue(outcomes[2].isSuccess)
        assertEquals(listOf("e3"), sink.events().map { it.id })
        assertEquals(2, sink.failedWriteCount())
        assertEquals(3, sink.attemptedEventCount())
        assertEquals(1, sink.writtenEventCount())
    }

    @Test
    fun `failEveryNthWrite follows a deterministic cadence`() = runBlocking {
        val sink = FakeLogSink().failEveryNthWrite(3)

        val failed = (1..9).map { index -> runCatching { sink.write(event("e$index")) }.isFailure }

        assertEquals(listOf(false, false, true, false, false, true, false, false, true), failed)
        assertEquals(3, sink.failedWriteCount())
        assertEquals(6, sink.count())
    }

    @Test
    fun `a failing batch records none of its events`() = runBlocking {
        val sink = FakeLogSink().failNextWrites(1)

        val result = runCatching { sink.writeAll(listOf(event("a"), event("b"))) }

        assertTrue(result.isFailure)
        assertEquals(0, sink.count())
        assertEquals(2, sink.attemptedEventCount())
        assertEquals(0, sink.writtenEventCount())
    }

    @Test
    fun `flush and close failures are configurable and resettable`() = runBlocking {
        val sink = FakeLogSink()
            .failFlushWith(IllegalStateException("disk full"))
            .failCloseWith(IllegalStateException("lease lost"))

        assertTrue(runCatching { sink.flush() }.isFailure)
        assertTrue(runCatching { sink.close() }.isFailure)
        assertEquals(1, sink.flushCallCount())
        assertEquals(1, sink.closeCallCount())

        sink.failFlushWith(null).failCloseWith(null)
        sink.flush()
        sink.close()
        assertEquals(2, sink.flushCallCount())
        assertEquals(2, sink.closeCallCount())
    }

    @Test
    fun `configured delay uses the test scheduler`() = runTest {
        val sink = FakeLogSink().delayWrites(2_000)
        val startedAt = testScheduler.currentTime

        sink.write(event("slow"))

        assertEquals(2_000, testScheduler.currentTime - startedAt)
        assertEquals(1, sink.count())
    }

    @Test
    fun `reset clears recordings counters and configuration`() = runBlocking {
        val sink = FakeLogSink()
            .failNextWrites(1)
            .failFlushWith(IllegalStateException("flush"))
            .delayWrites(10)
        runCatching { sink.write(event("a")) }
        sink.write(event("b"))
        assertEquals(1, sink.count())

        sink.reset()

        assertEquals(0, sink.count())
        assertEquals(0, sink.writeCallCount())
        assertEquals(0, sink.attemptedEventCount())
        assertEquals(0, sink.failedWriteCount())
        sink.flush()
        sink.write(event("c"))
        assertEquals(listOf("c"), sink.events().map { it.id })
    }

    @Test
    fun `configuration arguments are validated`() {
        val sink = FakeLogSink()

        assertThrows(IllegalArgumentException::class.java) { sink.failNextWrites(0) }
        assertThrows(IllegalArgumentException::class.java) { sink.failEveryNthWrite(-1) }
        assertThrows(IllegalArgumentException::class.java) { sink.delayWrites(-1) }
    }

    @Test
    fun `a zero cadence disables failure injection`() = runBlocking {
        val sink = FakeLogSink().failEveryNthWrite(0)

        sink.write(event("a"))
        sink.write(event("b"))

        assertEquals(0, sink.failedWriteCount())
        assertEquals(2, sink.count())
    }

    @Test
    fun `empty batches are recorded and direct writes honour failures`() = runBlocking {
        val sink = FakeLogSink()

        sink.writeAll(emptyList())

        assertEquals(1, sink.writeAllCallCount())
        assertEquals(listOf(emptyList<LogEvent>()), sink.batches())
        assertEquals(0, sink.attemptedEventCount())
        assertEquals(0, sink.count())

        sink.failNextWrites(1)
        assertTrue(runCatching { sink.writeDirect(event("x")) }.isFailure)
        assertEquals(1, sink.attemptedEventCount())
        assertEquals(0, sink.writtenEventCount())
        assertEquals(1, sink.failedWriteCount())

        sink.writeDirect(event("y"))
        assertEquals(listOf("y"), sink.events().map { it.id })
        assertEquals(1, sink.payloads().size)
    }

    @Test
    fun `reset clears batches payloads and close counters`() = runBlocking {
        val sink = FakeLogSink()
        sink.writeAll(listOf(event("a"), event("b")))
        sink.close()
        assertFalse(sink.batches().isEmpty())
        assertFalse(sink.payloads().isEmpty())

        sink.reset()

        assertTrue(sink.batches().isEmpty())
        assertTrue(sink.payloads().isEmpty())
        assertEquals(0, sink.writeAllCallCount())
        assertEquals(0, sink.writtenEventCount())
        assertEquals(0, sink.closeCallCount())
        assertEquals(null, sink.last())
    }
}
