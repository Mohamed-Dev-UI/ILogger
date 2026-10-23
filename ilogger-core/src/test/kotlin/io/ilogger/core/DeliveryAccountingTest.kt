package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

class DeliveryAccountingTest {

    @Test
    fun `flush is a true barrier for events enqueued before the call`() = runBlocking {
        val sink = object : LogSink {
            val events = Collections.synchronizedList(ArrayList<LogEvent>())

            override suspend fun write(event: LogEvent) {
                delay(10)
                events.add(event)
            }
        }
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sink(sink)
        }
        repeat(10) { log.record(action = "e.$it") }

        val report = log.flush(5_000)

        assertFalse(report.toString(), report.timedOut)
        assertEquals(10, sink.events.size)
        assertEquals(10L, report.accepted)
        assertEquals(10L, report.delivered)
        assertEquals(0, report.pending)
        assertEquals(0, report.inFlight)
        log.close()
    }

    @Test
    fun `flush times out and reports pending and in-flight work`() = runBlocking {
        val sink = BlockingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 16
            sink(sink)
        }
        repeat(3) { log.record(action = "e.$it") }
        assertTrue(sink.started.await(2, TimeUnit.SECONDS))

        val report = log.flush(100)

        assertTrue(report.timedOut)
        assertEquals(1, report.inFlight)
        assertEquals(2, report.pending)
        assertEquals(0L, report.delivered)

        sink.release.countDown()
        val drained = log.flush(2_000)
        assertFalse(drained.timedOut)
        assertEquals(3L, drained.delivered)
        log.close()
    }

    @Test
    fun `flush barrier settles after DROP_OLDEST evictions`() = runBlocking {
        val sink = BlockingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 1
            overflow = OverflowPolicy.DROP_OLDEST
            sink(sink)
        }
        try {
            log.record(action = "first")
            assertTrue(sink.started.await(2, TimeUnit.SECONDS))
            log.record(action = "second")
            log.record(action = "third")

            sink.release.countDown()
            val report = log.flush(2_000)

            assertFalse(report.toString(), report.timedOut)
            assertEquals(2, sink.count())
            assertEquals(1L, report.droppedByReason[DropReason.OVERFLOW_OLDEST])
        } finally {
            sink.release.countDown()
            log.close()
        }
    }

    @Test
    fun `close drains buffered pipelines and reports delivery`() = runBlocking {
        val sink = RecordingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.BUFFERED
            batchSize = 8
            flushIntervalMillis = 50
            sink(sink)
        }
        repeat(20) { log.record(action = "e.$it") }

        val report = log.closeAndReport()

        assertFalse(report.timedOut)
        assertEquals(20, sink.count())
        assertEquals(20L, report.delivered)
        assertEquals(0, report.pending)
        assertTrue(report.sinkFailures.isEmpty())
    }

    @Test
    fun `close reports sink failures and still closes every sink`() {
        var secondClosed = false
        val failing = object : LogSink {
            override suspend fun write(event: LogEvent) {}
            override fun close() {
                throw IllegalStateException("cannot close")
            }
        }
        val healthy = object : LogSink {
            override suspend fun write(event: LogEvent) {}
            override fun close() {
                secondClosed = true
            }
        }
        val log = ILogger.create {
            sink(failing)
            sink(healthy)
        }

        val report = log.closeAndReport()

        assertEquals(1, report.sinkFailures.size)
        assertTrue(secondClosed)
        assertEquals(report, log.closeAndReport()) // idempotent
    }

    @Test
    fun `concurrent producers do not lose events`() = runBlocking {
        val sink = RecordingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sink(sink)
        }
        coroutineScope {
            repeat(20) { producer ->
                launch(Dispatchers.Default) {
                    repeat(50) { index -> log.record(action = "p$producer.$index") }
                }
            }
        }

        val report = log.flush(10_000)

        assertFalse(report.timedOut)
        assertEquals(1_000, sink.count())
        assertEquals(1_000L, report.accepted)
        assertEquals(1_000L, report.delivered)
        assertEquals(1_000L, log.stats().accepted)
        log.close()
    }

    @Test
    fun `awaitIdle waits without flushing sinks`() = runBlocking {
        var flushes = 0
        val sink = object : LogSink {
            override suspend fun write(event: LogEvent) {
                delay(5)
            }

            override suspend fun flush() {
                flushes += 1
            }
        }
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sink(sink)
        }
        repeat(5) { log.record(action = "e.$it") }

        assertTrue(log.awaitIdle(2_000))
        assertEquals(0, flushes)

        log.flush(2_000)
        assertEquals(1, flushes)
        log.close()
    }
}
