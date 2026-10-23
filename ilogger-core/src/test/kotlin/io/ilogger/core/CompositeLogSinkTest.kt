package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.sink.CompositeLogSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

private fun event(index: Int): LogEvent = LogEvent(
    id = "e$index",
    timestampMillis = 1_000L + index,
    level = LogLevel.AUDIT,
    action = "e.$index",
)

class CompositeLogSinkTest {

    @Test
    fun `writeAll forwards one whole batch per inner sink`() = runBlocking {
        val first = BatchSink()
        val second = BatchSink()
        val composite = CompositeLogSink(first, second)
        val events = listOf(event(1), event(2), event(3))

        composite.writeAll(events)

        assertEquals(1, first.batches.size)
        assertEquals(1, second.batches.size)
        assertEquals(events, first.batches[0])
        assertEquals(events, second.batches[0])
        assertEquals(0, first.singleWrites.get())
        assertEquals(0, second.singleWrites.get())
    }

    @Test
    fun `BEST_EFFORT isolates a failing sink and reports it`() = runBlocking {
        val failing = BatchSink(failOnBatch = true)
        val healthy = BatchSink()
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val composite = CompositeLogSink(
            listOf(failing, healthy),
            onError = { _, _, error -> errors.add(error) },
        )

        composite.writeAll(listOf(event(1), event(2)))

        assertEquals(1, healthy.batches.size)
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalStateException)
    }

    @Test
    fun `FAIL_FAST propagates and skips remaining sinks`() = runBlocking {
        val failing = BatchSink(failOnBatch = true)
        val healthy = BatchSink()
        val composite = CompositeLogSink(
            listOf(failing, healthy),
            errorMode = CompositeLogSink.ErrorMode.FAIL_FAST,
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { composite.writeAll(listOf(event(1), event(2))) }
        }
        assertEquals(0, healthy.batches.size)
    }

    @Test
    fun `a throwing error handler does not stop delivery`() = runBlocking {
        val failing = BatchSink(failOnBatch = true)
        val healthy = BatchSink()
        val composite = CompositeLogSink(
            listOf(failing, healthy),
            onError = { _, _, _ -> throw IllegalStateException("broken observer") },
        )

        composite.writeAll(listOf(event(1)))

        assertEquals(1, healthy.batches.size)
    }

    @Test
    fun `empty batches are not forwarded`() = runBlocking {
        val sink = BatchSink()
        val composite = CompositeLogSink(sink)

        composite.writeAll(emptyList())

        assertEquals(0, sink.batches.size)
        assertEquals(0, sink.singleWrites.get())
    }

    @Test
    fun `writeDirect fans out to direct sinks`() {
        val first = RecordingSink()
        val second = RecordingSink()
        val composite = CompositeLogSink(first, second)

        composite.writeDirect(event(1))

        assertEquals(listOf("e.1"), first.actions())
        assertEquals(listOf("e.1"), second.actions())
    }

    @Test
    fun `writeDirect rejects composites containing non-direct sinks`() {
        val composite = CompositeLogSink(BatchSink())

        assertThrows(IllegalStateException::class.java) {
            composite.writeDirect(event(1))
        }
    }

    @Test
    fun `engine buffered batches reach inner sinks as one batch`() = runBlocking {
        val first = BatchSink()
        val second = BatchSink()
        val composite = CompositeLogSink(first, second)
        val log = ILogger.create {
            dispatch = DispatchMode.BUFFERED
            bufferSize = 32
            batchSize = 5
            flushIntervalMillis = 50
            sink(composite)
        }
        repeat(5) { log.record(action = "e.$it") }

        val report = log.flush(5_000)

        assertFalse(report.timedOut)
        assertEquals(1, first.batches.size)
        assertEquals(1, second.batches.size)
        assertEquals(5, first.writtenCount())
        assertEquals(0, first.singleWrites.get())
        assertEquals(0, second.singleWrites.get())
        log.close()
    }
}
