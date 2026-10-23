package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackpressureHelpersTest {

    private class PlainSink : LogSink {
        override suspend fun write(event: LogEvent) = Unit
    }

    @Test
    fun `tryRecord admits events and reports closed or disabled loggers`() = runBlocking {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.ASYNC
        }
        assertTrue(logger.hasSinks())
        assertTrue(logger.tryRecord("op.one"))
        logger.flush()
        assertEquals(listOf("op.one"), sink.actions())
        logger.close()

        assertFalse(logger.tryRecord("op.two"))
        assertFalse(logger.hasSinks())

        val disabled = ILogger.create {
            sink(RecordingSink())
            enabled = false
        }
        assertFalse(disabled.hasSinks())
        assertFalse(disabled.tryRecord("op.three"))
        assertEquals(0, disabled.availableCapacity())
        assertFalse(disabled.isBacklogged())
        disabled.close()
    }

    @Test
    fun `tryRecord never blocks or throws when queues are full`() = runBlocking {
        for (policy in listOf(OverflowPolicy.BLOCK, OverflowPolicy.FAIL)) {
            val sink = BlockingSink()
            val logger = ILogger.create {
                sink(sink)
                dispatch = DispatchMode.ASYNC
                bufferSize = 2
                overflow = policy
                blockTimeoutMillis = 5_000
            }
            logger.recordNow("first")
            assertTrue("worker did not start under $policy", sink.started.await(5, TimeUnit.SECONDS))
            logger.recordNow("second")
            logger.recordNow("third")

            assertEquals(2, logger.stats().pending)
            assertEquals(0, logger.availableCapacity())
            assertTrue(logger.isBacklogged())

            val elapsed = measureTimeMillis {
                assertFalse("policy=$policy", logger.tryRecord("fourth"))
            }
            assertTrue("tryRecord blocked for $elapsed ms under $policy", elapsed < 1_000)
            assertEquals(1L, logger.stats().droppedByReason[DropReason.OVERFLOW_NEWEST] ?: 0L)

            sink.release.countDown()
            assertFalse(logger.flush(5_000).timedOut)
            logger.close()
            assertEquals(3, sink.count())
        }
    }

    @Test
    fun `tryRecord honors drop policies without blocking`() = runBlocking {
        val oldestSink = BlockingSink()
        val oldest = ILogger.create {
            sink(oldestSink)
            dispatch = DispatchMode.ASYNC
            bufferSize = 2
            overflow = OverflowPolicy.DROP_OLDEST
        }
        oldest.recordNow("first")
        assertTrue(oldestSink.started.await(5, TimeUnit.SECONDS))
        oldest.recordNow("second")
        oldest.recordNow("third")
        assertTrue(oldest.tryRecord("fourth"))
        assertEquals(1L, oldest.stats().droppedByReason[DropReason.OVERFLOW_OLDEST] ?: 0L)
        oldestSink.release.countDown()
        assertFalse(oldest.flush(5_000).timedOut)
        oldest.close()

        val newestSink = BlockingSink()
        val newest = ILogger.create {
            sink(newestSink)
            dispatch = DispatchMode.ASYNC
            bufferSize = 2
            overflow = OverflowPolicy.DROP_NEWEST
        }
        newest.recordNow("first")
        assertTrue(newestSink.started.await(5, TimeUnit.SECONDS))
        newest.recordNow("second")
        newest.recordNow("third")
        assertFalse(newest.tryRecord("fourth"))
        assertEquals(1L, newest.stats().droppedByReason[DropReason.OVERFLOW_NEWEST] ?: 0L)
        newestSink.release.countDown()
        assertFalse(newest.flush(5_000).timedOut)
        newest.close()
    }

    @Test
    fun `tryRecord inline writes direct sinks and rejects blocking ones`() = runBlocking {
        val direct = RecordingSink()
        val inline = ILogger.create {
            sink(direct)
            dispatch = DispatchMode.INLINE
        }
        assertTrue(inline.tryRecord("op.inline"))
        assertEquals(listOf("op.inline"), direct.actions())
        inline.close()

        val blocking = BlockingSink()
        val guarded = ILogger.create {
            sink(blocking)
            dispatch = DispatchMode.INLINE
        }
        val elapsed = measureTimeMillis { assertFalse(guarded.tryRecord("op.blocked")) }
        assertTrue("tryRecord blocked for $elapsed ms", elapsed < 1_000)
        assertEquals(0, blocking.count())
        assertEquals(1L, guarded.stats().droppedByReason[DropReason.SINK_FAILURE] ?: 0L)
        guarded.close()
    }

    @Test
    fun `tryRecord accepts non-direct sinks on asynchronous dispatch`() = runBlocking {
        val logger = ILogger.create {
            sink(PlainSink())
            dispatch = DispatchMode.ASYNC
        }
        assertTrue(logger.tryRecord("op.plain"))
        assertFalse(logger.flush(5_000).timedOut)
        assertEquals(1L, logger.stats().delivered)
        logger.close()
    }

    @Test
    fun `tryRecord swallows strict THROW rejects`() = runBlocking {
        val drops = CopyOnWriteArrayList<DropReason>()
        val logger = ILogger.create {
            sink(RecordingSink())
            strictMode = StrictMode.THROW
            onDrop = { drops += it.reason }
        }
        assertFalse(logger.tryRecord("strict.op", metadata = mapOf("opaque" to Any())))
        assertTrue(DropReason.STRICT_REJECT in drops)
        logger.close()
    }

    @Test
    fun `recordOrDrop is a best-effort unit call`() = runBlocking {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.ASYNC
        }
        logger.recordOrDrop("op.drop")
        logger.flush()
        assertEquals(listOf("op.drop"), sink.actions())
        logger.close()
    }

    @Test
    fun `stats expose per-binding depth and counters`() = runBlocking {
        val catchAllSink = RecordingSink()
        val specialSink = RecordingSink()
        val logger = ILogger.create {
            sink(catchAllSink)
            sink(specialSink, category = "special")
            dispatch = DispatchMode.ASYNC
            bufferSize = 8
        }
        logger.recordNow("op.default")
        logger.recordNow("op.special", category = "special")
        logger.flush()

        val stats = logger.stats()
        assertEquals(2, stats.bindings.size)
        val catchAll = stats.bindings.first { it.category == null }
        val special = stats.bindings.first { it.category == "special" }
        assertEquals(2L, catchAll.accepted)
        assertEquals(2L, catchAll.delivered)
        assertEquals(1L, special.accepted)
        assertEquals(1L, special.delivered)
        assertEquals(0, catchAll.pending)
        assertEquals(0, catchAll.inFlight)
        assertTrue(catchAll.sink.contains("RecordingSink"))
        assertEquals(listOf("op.default", "op.special"), catchAllSink.actions())
        assertEquals(listOf("op.special"), specialSink.actions())
        assertEquals(8, logger.availableCapacity())
        logger.close()
    }

    @Test
    fun `availableCapacity is the most constrained binding`() = runBlocking {
        val blocked = BlockingSink()
        val fast = RecordingSink()
        val logger = ILogger.create {
            sink(blocked)
            sink(fast)
            dispatch = DispatchMode.ASYNC
            bufferSize = 2
        }
        logger.recordNow("first")
        assertTrue(blocked.started.await(5, TimeUnit.SECONDS))
        logger.recordNow("second")
        logger.recordNow("third")
        assertEquals(0, logger.availableCapacity())
        assertTrue(logger.isBacklogged())
        blocked.release.countDown()
        assertFalse(logger.flush(5_000).timedOut)
        assertEquals(2, logger.availableCapacity())
        assertFalse(logger.isBacklogged())
        logger.close()
    }
}
