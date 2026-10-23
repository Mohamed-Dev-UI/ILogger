package io.ilogger.testing

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingLogSinkTest {

    private fun event(index: Int): LogEvent =
        LogEvent(id = "blocked-$index", timestampMillis = 1_000, level = LogLevel.AUDIT, action = "blocked.$index")

    @Test
    fun `writes wait for release and signal the first attempt`() = runBlocking {
        val sink = BlockingLogSink()
        val write = async(Dispatchers.Default) { sink.write(event(1)) }

        assertTrue(sink.awaitFirstWrite(2_000))
        delay(50)
        assertFalse(write.isCompleted)
        assertEquals(0, sink.count())

        sink.release()
        write.await()

        assertEquals(1, sink.count())
        assertEquals("blocked-1", sink.events().single().id)
    }

    @Test
    fun `close releases blocked writers`() = runBlocking {
        val sink = BlockingLogSink()
        val write = async(Dispatchers.Default) { sink.write(event(1)) }

        assertTrue(sink.awaitFirstWrite(2_000))
        sink.close()
        write.await()

        assertEquals(1, sink.count())
    }

    @Test
    fun `cancellation while waiting records nothing`() = runBlocking {
        val sink = BlockingLogSink()
        val write = launch(Dispatchers.Default) { sink.write(event(1)) }

        assertTrue(sink.awaitFirstWrite(2_000))
        write.cancelAndJoin()
        sink.release()

        assertEquals(0, sink.count())
    }

    @Test
    fun `writeAll holds the whole batch until release and preserves order`() = runBlocking {
        val sink = BlockingLogSink(name = "batched")
        assertEquals("batched", sink.name)
        assertFalse(sink.firstWriteReached())

        val batch = async(Dispatchers.Default) { sink.writeAll(List(3) { event(it) }) }

        assertTrue(sink.awaitFirstWrite(2_000))
        assertTrue(sink.firstWriteReached())
        delay(50)
        assertEquals(0, sink.count())

        sink.release()
        batch.await()

        assertEquals(listOf("blocked-0", "blocked-1", "blocked-2"), sink.events().map { it.id })
        assertEquals(3, sink.count())
    }

    @Test
    fun `an empty batch neither records nor signals a first write`() = runBlocking {
        val sink = BlockingLogSink()

        sink.writeAll(emptyList())

        assertFalse(sink.firstWriteReached())
        assertEquals(0, sink.count())
    }

    @Test
    fun `awaitFirstWrite reports a timeout when nothing writes`() = runBlocking {
        val sink = BlockingLogSink()

        assertFalse(sink.awaitFirstWrite(timeoutMillis = 10))
        assertFalse(sink.firstWriteReached())
    }

    @Test
    fun `release before any write lets later writes pass immediately`() = runBlocking {
        val sink = BlockingLogSink()
        sink.release()

        withTimeout(1_000) { sink.write(event(1)) }

        assertEquals(1, sink.count())
    }
}
