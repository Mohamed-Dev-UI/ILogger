package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DropReportingTest {

    private val drops = Collections.synchronizedList(ArrayList<DroppedEvent>())

    private fun reasons(reason: DropReason): List<DroppedEvent> =
        synchronized(drops) { drops.filter { it.reason == reason } }

    @Test
    fun `DROP_NEWEST overflow reports the rejected event`() = runBlocking {
        val sink = BlockingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 1
            overflow = OverflowPolicy.DROP_NEWEST
            sink(sink)
            onDrop = { drops.add(it) }
        }
        try {
            log.record(action = "first")
            assertTrue(sink.started.await(2, TimeUnit.SECONDS))
            log.record(action = "second")
            log.record(action = "third")

            val overflowed = reasons(DropReason.OVERFLOW_NEWEST)
            assertEquals(1, overflowed.size)
            assertEquals("third", overflowed[0].event?.action)
            assertEquals(1L, log.stats().droppedByReason[DropReason.OVERFLOW_NEWEST])
        } finally {
            sink.release.countDown()
            log.close()
        }
    }

    @Test
    fun `DROP_OLDEST overflow reports the evicted event`() = runBlocking {
        val sink = BlockingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 1
            overflow = OverflowPolicy.DROP_OLDEST
            sink(sink)
            onDrop = { drops.add(it) }
        }
        try {
            log.record(action = "first")
            assertTrue(sink.started.await(2, TimeUnit.SECONDS))
            log.record(action = "second")
            log.record(action = "third")

            val evicted = reasons(DropReason.OVERFLOW_OLDEST)
            assertEquals(1, evicted.size)
            assertEquals("second", evicted[0].event?.action)
        } finally {
            sink.release.countDown()
            log.flush(2_000)
            assertEquals(listOf("first", "third"), sink.events.map { it.action })
            log.close()
        }
    }

    @Test
    fun `events produced after close report CLOSED`() {
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(RecordingSink())
            onDrop = { drops.add(it) }
        }
        log.close()

        log.recordNow(action = "late.event")

        val closed = reasons(DropReason.CLOSED)
        assertEquals(1, closed.size)
        assertNull(closed[0].event)
    }

    @Test
    fun `retry exhaustion and drop mode report distinct reasons`() = runBlocking {
        val failing = object : LogSink {
            override suspend fun write(event: LogEvent) {
                throw IllegalStateException("sink down")
            }
        }
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
            retryMaxAttempts = 1
            retryBaseBackoffMillis = 0
            sink(failing)
            onDrop = { drops.add(it) }
        }
        log.record(action = "retry.event")
        log.close()
        assertEquals(1, reasons(DropReason.RETRY_EXHAUSTED).size)

        drops.clear()
        val dropLog = ILogger.create {
            dispatch = DispatchMode.INLINE
            onErrorMode(LogLevel.AUDIT, ErrorMode.DROP)
            sink(failing)
            onDrop = { drops.add(it) }
        }
        dropLog.record(action = "drop.event")
        dropLog.close()
        assertEquals(1, reasons(DropReason.SINK_FAILURE).size)
    }

    @Test
    fun `sampled diagnostics report SAMPLED without allocating an event`() {
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            sampling(LogLevel.DEBUG to 0.0)
            sink(RecordingSink())
            onDrop = { drops.add(it) }
        }

        log.debug("quiet")

        val sampled = reasons(DropReason.SAMPLED)
        assertEquals(1, sampled.size)
        assertNull(sampled[0].event)
        log.close()
    }

    @Test
    fun `suppressed sources report SUPPRESSED`() = runBlocking {
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(RecordingSink())
            onDrop = { drops.add(it) }
        }

        withLogContext(LogContext(suppressSources = setOf("TaskDao.insert"))) {
            log.record(action = "task.created", source = "TaskDao.insert")
        }

        val suppressed = reasons(DropReason.SUPPRESSED)
        assertEquals(1, suppressed.size)
        assertNull(suppressed[0].event)
        log.close()
    }

    @Test
    fun `events with no matching binding report MISSING_BINDING`() = runBlocking {
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            onDrop = { drops.add(it) }
        }

        log.record(action = "orphan.event")

        val missing = reasons(DropReason.MISSING_BINDING)
        assertEquals(1, missing.size)
        assertEquals("orphan.event", missing[0].event?.action)
        log.close()
    }

    @Test
    fun `dead-letter sink receives overflowed events with their reason`() = runBlocking {
        val sink = BlockingSink()
        val deadLetters = Collections.synchronizedList(ArrayList<DroppedEvent>())
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 1
            overflow = OverflowPolicy.DROP_NEWEST
            sink(sink)
            deadLetter(object : DeadLetterSink {
                override suspend fun write(dropped: DroppedEvent) {
                    deadLetters.add(dropped)
                }
            })
        }
        try {
            log.record(action = "first")
            assertTrue(sink.started.await(2, TimeUnit.SECONDS))
            log.record(action = "second")
            log.record(action = "third")

            assertTrue(awaitUntil(2_000) { deadLetters.any { it.event?.action == "third" } })
            assertEquals(DropReason.OVERFLOW_NEWEST, deadLetters.first { it.event?.action == "third" }.reason)
        } finally {
            sink.release.countDown()
            log.close()
        }
    }

    @Test
    fun `every overflowed event is counted exactly once`() = runBlocking {
        val sink = BlockingSink()
        val counter = AtomicInteger()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 1
            overflow = OverflowPolicy.DROP_NEWEST
            sink(sink)
            onDrop = { if (it.reason == DropReason.OVERFLOW_NEWEST) counter.incrementAndGet() }
        }
        try {
            log.record(action = "first")
            assertTrue(sink.started.await(2, TimeUnit.SECONDS))
            log.record(action = "second")
            repeat(10) { log.record(action = "burst.$it") }

            assertEquals(10, counter.get())
            assertEquals(10L, log.stats().droppedByReason[DropReason.OVERFLOW_NEWEST])
        } finally {
            sink.release.countDown()
            log.close()
        }
    }
}
