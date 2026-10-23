package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Slow but cancellable: [delay] cooperates with the delivery timeout. */
private class SlowSuspendingSink : LogSink {
    val events = Collections.synchronizedList(ArrayList<LogEvent>())

    override suspend fun write(event: LogEvent) {
        delay(10_000)
        events.add(event)
    }
}

/** Proves the engine used [DirectLogSink.writeDirect], not coroutine dispatch. */
private class DirectOnlySink : DirectLogSink {
    val direct = Collections.synchronizedList(ArrayList<LogEvent>())

    override suspend fun write(event: LogEvent) {
        throw AssertionError("coroutine write path must not be used for an INLINE diagnostic")
    }

    override fun writeDirect(event: LogEvent) {
        direct.add(event)
    }
}

class BlockingRecordingTest {

    @Test
    fun `recordBlocking INLINE delivers before returning`() {
        val sink = RecordingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sink(sink)
        }

        val result = log.recordBlocking(action = "task.created", dispatch = DispatchMode.INLINE)

        assertTrue(result)
        assertEquals(listOf("task.created"), sink.actions())
        assertEquals(1L, log.stats().delivered)
        log.close()
    }

    @Test
    fun `recordBlocking INLINE honours the explicit timeout`() {
        val sink = SlowSuspendingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
        }

        val started = System.nanoTime()
        val result = log.recordBlocking(
            action = "slow.inline",
            dispatch = DispatchMode.INLINE,
            timeoutMillis = 100,
        )
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertFalse(result)
        assertTrue("call took $elapsedMillis ms", elapsedMillis < 5_000)
        assertEquals(0, sink.events.size)
        assertEquals(1L, log.stats().droppedByReason[DropReason.SINK_FAILURE])
        log.close()
    }

    @Test
    fun `recordBlocking BLOCK admission succeeds once capacity frees`() {
        val sink = BlockingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            overflow = OverflowPolicy.BLOCK
            bufferSize = 1
            blockTimeoutMillis = 10_000
            sink(sink)
        }
        try {
            assertTrue(log.recordBlocking(action = "first"))
            assertTrue(sink.started.await(2, TimeUnit.SECONDS))
            assertTrue(log.recordBlocking(action = "second"))

            thread(name = "sink-releaser") {
                Thread.sleep(200)
                sink.release.countDown()
            }

            val accepted = log.recordBlocking(action = "third", timeoutMillis = 5_000)

            assertTrue(accepted)
            val deadline = System.nanoTime() + 2_000_000_000
            while (sink.count() < 3 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(3, sink.count())
            assertEquals(0L, log.stats().droppedByReason[DropReason.OVERFLOW_NEWEST] ?: 0L)
        } finally {
            sink.release.countDown()
            log.close()
        }
    }

    @Test
    fun `recordBlocking BLOCK admission times out without exceeding its budget`() {
        val sink = BlockingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            overflow = OverflowPolicy.BLOCK
            bufferSize = 1
            blockTimeoutMillis = 10_000
            sink(sink)
        }
        try {
            assertTrue(log.recordBlocking(action = "first"))
            assertTrue(sink.started.await(2, TimeUnit.SECONDS))
            assertTrue(log.recordBlocking(action = "second"))

            val started = System.nanoTime()
            val accepted = log.recordBlocking(action = "third", timeoutMillis = 150)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000

            assertFalse(accepted)
            assertTrue("call took $elapsedMillis ms", elapsedMillis >= 100)
            assertTrue("call took $elapsedMillis ms", elapsedMillis < 5_000)
            assertEquals(1L, log.stats().droppedByReason[DropReason.OVERFLOW_NEWEST])
        } finally {
            sink.release.countDown()
            log.close()
        }
    }

    @Test
    fun `recordNow INLINE is bounded by the configured timeout`() {
        val sink = SlowSuspendingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            blockTimeoutMillis = 100
            sink(sink)
        }

        val started = System.nanoTime()
        log.recordNow(action = "slow.recordNow", dispatch = DispatchMode.INLINE)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("call took $elapsedMillis ms", elapsedMillis < 5_000)
        assertEquals(0, sink.events.size)
        assertEquals(1L, log.stats().droppedByReason[DropReason.SINK_FAILURE])
        log.close()
    }

    @Test
    fun `recordBlocking returns false for sampled events`() {
        val sink = RecordingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            sampling(LogLevel.DEBUG to 0.0)
            sink(sink)
        }

        val result = log.recordBlocking(action = "log.debug", level = LogLevel.DEBUG)

        assertFalse(result)
        assertEquals(0, sink.count())
        assertEquals(1L, log.stats().droppedByReason[DropReason.SAMPLED])
        log.close()
    }

    @Test
    fun `diagnostics never block on a saturated queue`() {
        val sink = BlockingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            overflow = OverflowPolicy.BLOCK
            bufferSize = 1
            blockTimeoutMillis = 10_000
            sink(sink)
        }
        try {
            assertTrue(log.recordBlocking(action = "first"))
            assertTrue(sink.started.await(2, TimeUnit.SECONDS))
            assertTrue(log.recordBlocking(action = "second"))

            val started = System.nanoTime()
            log.info("heartbeat", "queue" to "full")
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000

            assertTrue("diagnostic took $elapsedMillis ms", elapsedMillis < 2_000)
            assertEquals(1L, log.stats().droppedByReason[DropReason.OVERFLOW_NEWEST])
            assertEquals(1, log.stats().pending)
        } finally {
            sink.release.countDown()
            log.close()
        }
    }

    @Test
    fun `INLINE diagnostics use the direct sink fast path`() {
        val sink = DirectOnlySink()
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
        }

        log.info("direct.path")

        assertEquals(1, sink.direct.size)
        assertEquals(1L, log.stats().delivered)
        log.close()
    }

    @Test
    fun `INLINE diagnostics to ordinary sinks still write synchronously`() {
        val sink = BatchSink()
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
        }

        log.info("fallback.path")

        assertEquals(1, sink.singleWrites.get())
        log.close()
    }
}
