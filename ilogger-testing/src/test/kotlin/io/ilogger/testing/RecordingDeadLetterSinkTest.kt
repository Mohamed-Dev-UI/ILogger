package io.ilogger.testing

import io.ilogger.core.DropReason
import io.ilogger.core.DroppedEvent
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeadLetterSinkTest {

    private fun drop(reason: DropReason, message: String): DroppedEvent =
        DroppedEvent(event = null, reason = reason, error = IllegalStateException(message))

    @Test
    fun `records drops in arrival order and exposes immutable snapshots`() = runBlocking {
        val sink = RecordingDeadLetterSink()
        sink.write(drop(DropReason.SAMPLED, "a"))
        sink.write(drop(DropReason.SINK_FAILURE, "b"))

        val snapshot = sink.dropped()
        sink.write(drop(DropReason.OVERFLOW_NEWEST, "c"))

        assertEquals(2, snapshot.size)
        assertEquals(3, sink.count())
        assertEquals(
            listOf(DropReason.SAMPLED, DropReason.SINK_FAILURE, DropReason.OVERFLOW_NEWEST),
            sink.reasons(),
        )
        assertEquals("a", snapshot.first().error!!.message)
    }

    @Test
    fun `counts flush and close calls`() = runBlocking {
        val sink = RecordingDeadLetterSink()
        assertEquals(0, sink.count())
        assertEquals(0, sink.flushCallCount())
        assertEquals(0, sink.closeCallCount())

        sink.flush()
        sink.flush()
        sink.close()

        assertEquals(2, sink.flushCallCount())
        assertEquals(1, sink.closeCallCount())
        assertTrue(sink.dropped().isEmpty())
    }

    @Test
    fun `concurrent writers lose nothing`() = runBlocking {
        val sink = RecordingDeadLetterSink()
        val threads = 4
        val perThread = 25
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { worker ->
            thread(name = "dead-letter-writer-$worker") {
                runBlocking {
                    start.await()
                    repeat(perThread) { index ->
                        sink.write(drop(DropReason.SINK_FAILURE, "$worker-$index"))
                    }
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(threads * perThread, sink.count())
        assertEquals(List(threads * perThread) { DropReason.SINK_FAILURE }, sink.reasons())
    }
}
