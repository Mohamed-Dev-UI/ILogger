package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultILoggerEdgeCasesTest {

    private class DirectFailingSink : DirectLogSink {
        val attempts = AtomicInteger()

        override suspend fun write(event: LogEvent): Unit = throw IllegalStateException("direct boom")

        override fun writeDirect(event: LogEvent) {
            attempts.incrementAndGet()
            throw IllegalStateException("direct boom")
        }
    }

    private class SlowSink : LogSink {
        override suspend fun write(event: LogEvent) {
            delay(10_000)
        }
    }

    private class FlushFailingSink : LogSink {
        val events = CopyOnWriteArrayList<LogEvent>()

        override suspend fun write(event: LogEvent) {
            events += event
        }

        override suspend fun flush() {
            throw IllegalStateException("flush boom")
        }
    }

    private class RecordingDeadLetterSink : DeadLetterSink {
        val events = CopyOnWriteArrayList<String>()

        override suspend fun write(dropped: DroppedEvent) {
            if (dropped.event?.action == "dl.fail") throw IllegalStateException("dl down")
            dropped.event?.let { events += it.action }
        }
    }

    private class BlockingDeadLetterSink : DeadLetterSink {
        val entered = CountDownLatch(1)
        private val gate = CompletableDeferred<Unit>()

        override suspend fun write(dropped: DroppedEvent) {
            entered.countDown()
            gate.await()
        }
    }

    private class FlushFailingDeadLetterSink : DeadLetterSink {
        override suspend fun write(dropped: DroppedEvent) {}

        override suspend fun flush() {
            throw IllegalStateException("dl flush boom")
        }
    }

    private class CloseFailingDeadLetterSink : DeadLetterSink {
        override suspend fun write(dropped: DroppedEvent) {}

        override fun close() {
            throw IllegalStateException("dl close boom")
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    @Test
    fun `diagnostics map every level to its canonical action and metadata`() {
        val sink = RecordingSink()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
        }

        logger.trace("trace message", "field" to 1)
        logger.debug("debug message")
        logger.info("info message")
        logger.warn("warn message")
        logger.error("error message")

        assertEquals(
            listOf("log.trace", "log.debug", "log.info", "log.warn", "log.error"),
            sink.actions(),
        )
        assertEquals(
            listOf(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            sink.events.map { it.level },
        )
        assertEquals("trace message", sink.events.first().metadata["message"])
        assertEquals(1, sink.events.first().metadata["field"])
        assertEquals("ILogger", sink.events.first().source)
        assertEquals(5L, logger.stats().delivered)
        logger.close()
    }

    @Test
    fun `diagnostic after close reports CLOSED without an event`() {
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(RecordingSink())
            onDrop = { drops += it }
        }
        logger.close()

        logger.info("late")

        val closed = drops.single { it.reason == DropReason.CLOSED }
        assertNull(closed.event)
    }

    @Test
    fun `diagnostic without a binding reports MISSING_BINDING`() {
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onDrop = { drops += it }
        }

        logger.info("orphan")

        val missing = drops.single { it.reason == DropReason.MISSING_BINDING }
        assertEquals("log.info", missing.event?.action)
        assertEquals("orphan", missing.event?.metadata?.get("message"))
        logger.close()
    }

    @Test
    fun `direct diagnostic write failures are reported and counted`() {
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val sink = DirectFailingSink()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
            onDrop = { drops += it }
            onDispatchError = { _, _, error -> errors += error }
        }

        logger.info("boom")

        assertEquals(1, sink.attempts.get())
        assertEquals(1L, logger.stats().droppedByReason[DropReason.SINK_FAILURE])
        assertEquals("direct boom", drops.single().error?.message)
        assertEquals("direct boom", errors.single().message)
        logger.close()
    }

    @Test
    fun `inline diagnostic bounded wait reports a timeout drop`() {
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            blockTimeoutMillis = 50
            sink(SlowSink())
            onDrop = { drops += it }
        }

        logger.info("slow")

        val drop = drops.single { it.reason == DropReason.SINK_FAILURE }
        assertTrue(drop.error!!.message!!.contains("inline diagnostic timed out"))
        logger.close()
    }

    @Test
    fun `async diagnostics apply the overflow policy and report evictions`() = runBlocking {
        val sink = BlockingSink()
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 1
            overflow = OverflowPolicy.DROP_OLDEST
            sink(sink)
            onDrop = { drops += it }
        }
        try {
            logger.recordNow("occupied")
            assertTrue(sink.started.await(5, TimeUnit.SECONDS))
            logger.info("first")
            logger.info("second")

            assertTrue(
                "expected an eviction, got ${drops.map { it.reason }}",
                awaitUntil(2_000) { drops.any { it.reason == DropReason.OVERFLOW_OLDEST } },
            )
            val evicted = drops.first { it.reason == DropReason.OVERFLOW_OLDEST }.event!!
            assertEquals("log.info", evicted.action)
            assertEquals("first", evicted.metadata["message"])
        } finally {
            sink.release.countDown()
            logger.close()
        }
    }

    // ------------------------------------------------------------------
    // Admission results
    // ------------------------------------------------------------------

    @Test
    fun `empty category is normalized to the default route`() = runBlocking {
        val sink = RecordingSink()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
        }

        logger.record(action = "empty.category", category = "")

        assertNull(sink.events.single().category)
        logger.close()
    }

    @Test
    fun `recordBlocking without a binding returns false and reports the event`() {
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onDrop = { drops += it }
        }

        assertFalse(logger.recordBlocking("orphan.blocking"))

        assertEquals("orphan.blocking", drops.single { it.reason == DropReason.MISSING_BINDING }.event?.action)
        logger.close()
    }

    @Test
    fun `recordBlocking after close reports CLOSED and returns false`() {
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(RecordingSink())
            onDrop = { drops += it }
        }
        logger.close()

        assertFalse(logger.recordBlocking("late.blocking"))

        assertNull(drops.single { it.reason == DropReason.CLOSED }.event)
    }

    @Test
    fun `recordBlocking applies DROP_NEWEST and returns false when the queue is full`() {
        val sink = BlockingSink()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 1
            overflow = OverflowPolicy.DROP_NEWEST
            sink(sink)
        }
        try {
            logger.recordNow("running")
            assertTrue(sink.started.await(5, TimeUnit.SECONDS))
            logger.recordNow("queued")

            assertFalse(logger.recordBlocking("rejected"))

            assertEquals(1L, logger.stats().droppedByReason[DropReason.OVERFLOW_NEWEST])
            assertEquals(1, logger.stats().pending)
        } finally {
            sink.release.countDown()
            logger.close()
        }
    }

    @Test
    fun `FAIL admission throws and reports when the queue is full`() {
        val sink = BlockingSink()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 1
            overflow = OverflowPolicy.FAIL
            sink(sink)
        }
        try {
            logger.recordNow("running")
            assertTrue(sink.started.await(5, TimeUnit.SECONDS))
            logger.recordNow("queued")

            val nonSuspend = assertThrows(IllegalStateException::class.java) {
                logger.recordNow("rejected.recordNow")
            }
            assertTrue(nonSuspend.message!!.contains("buffer full"))

            val blocking = assertThrows(IllegalStateException::class.java) {
                logger.recordBlocking("rejected.recordBlocking")
            }
            assertTrue(blocking.message!!.contains("buffer full"))

            val suspending = runCatching {
                runBlocking { logger.record("rejected.record") }
            }.exceptionOrNull()
            assertTrue("expected IllegalStateException, got $suspending", suspending is IllegalStateException)

            assertEquals(3L, logger.stats().droppedByReason[DropReason.OVERFLOW_NEWEST])
        } finally {
            sink.release.countDown()
            logger.close()
        }
    }

    @Test
    fun `tryRecord inline returns false when a direct sink throws`() {
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val sink = DirectFailingSink()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
            onDrop = { drops += it }
        }

        assertFalse(logger.tryRecord("inline.fail"))

        assertEquals(1, sink.attempts.get())
        assertEquals(1L, logger.stats().droppedByReason[DropReason.SINK_FAILURE])
        assertEquals(DropReason.SINK_FAILURE, drops.single().reason)
        logger.close()
    }

    @Test
    fun `tryRecord without a binding returns false`() {
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
        }

        assertFalse(logger.tryRecord("orphan.try"))

        assertEquals(1L, logger.stats().droppedByReason[DropReason.MISSING_BINDING])
        logger.close()
    }

    // ------------------------------------------------------------------
    // Flush and close
    // ------------------------------------------------------------------

    @Test
    fun `flush after close replays the close report`() {
        val sink = RecordingSink()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
        }
        logger.recordNow("one")
        val close = logger.closeAndReport()

        val report = logger.flush(timeoutMillis = 0)

        assertEquals(close.accepted, report.accepted)
        assertEquals(close.delivered, report.delivered)
        assertEquals(close.pending, report.pending)
        assertEquals(close.inFlight, report.inFlight)
        assertEquals(close.droppedByReason, report.droppedByReason)
        assertFalse(report.timedOut)
        assertTrue(logger.awaitIdle(timeoutMillis = 0))
    }

    @Test
    fun `flush failures are reported without failing the barrier`() {
        val errors = CopyOnWriteArrayList<Throwable>()
        val sink = FlushFailingSink()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
            onDispatchError = { _, _, error -> errors += error }
        }
        logger.recordNow("flush.event")

        val report = logger.flush(timeoutMillis = 1_000)

        assertFalse(report.timedOut)
        assertEquals(1, sink.events.size)
        assertTrue(errors.any { it.message == "flush boom" })
        logger.close()
    }

    @Test
    fun `close timeout drains queued events as CLOSED`() {
        val sink = BlockingSink()
        val drops = CopyOnWriteArrayList<DroppedEvent>()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 8
            closeTimeoutMillis = 100
            sink(sink)
            onDrop = { drops += it }
        }

        val report: CloseReport
        try {
            logger.recordNow("running")
            assertTrue(sink.started.await(5, TimeUnit.SECONDS))
            logger.recordNow("queued.one")
            logger.recordNow("queued.two")
            report = logger.closeAndReport()
        } finally {
            sink.release.countDown()
        }

        assertTrue(report.timedOut)
        assertEquals(2L, report.droppedByReason[DropReason.CLOSED])
        assertEquals(0, report.pending)
        assertEquals(0L, report.delivered)
        assertEquals(1, report.inFlight)
        assertEquals(0, logger.availableCapacity())
        assertFalse(logger.isBacklogged())
        assertTrue(logger.awaitIdle(timeoutMillis = 0))
        assertEquals(2, drops.count { it.reason == DropReason.CLOSED })
    }

    @Test
    fun `close drains with concurrent sink workers`() {
        val sink = object : LogSink {
            val events = CopyOnWriteArrayList<LogEvent>()

            override suspend fun write(event: LogEvent) {
                delay(2)
                events += event
            }
        }
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sinkConcurrency = 3
            batchSize = 8
            flushIntervalMillis = 10
            sink(sink)
        }
        repeat(40) { logger.recordNow("concurrent.$it") }

        val report = logger.closeAndReport()

        assertFalse(report.timedOut)
        assertEquals(40, sink.events.size)
        assertEquals(40L, report.delivered)
    }

    @Test
    fun `close reports sink flush failures and still completes`() {
        val errors = CopyOnWriteArrayList<Throwable>()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sink(FlushFailingSink())
            onDispatchError = { _, _, error -> errors += error }
        }

        val report = logger.closeAndReport()

        assertFalse(report.timedOut)
        assertTrue(report.sinkFailures.isEmpty())
        assertTrue(errors.any { it.message == "flush boom" })
    }

    // ------------------------------------------------------------------
    // Dead-letter pipeline
    // ------------------------------------------------------------------

    @Test
    fun `dead-letter write failures are reported and later drops still arrive`() {
        val dead = RecordingDeadLetterSink()
        val errors = CopyOnWriteArrayList<Throwable>()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 8
            deadLetter(dead)
            onDispatchError = { _, _, error -> errors += error }
        }

        logger.recordNow("dl.fail")
        logger.recordNow("dl.ok.one")
        logger.recordNow("dl.ok.two")
        val report = logger.closeAndReport()

        assertFalse(report.timedOut)
        assertEquals(listOf("dl.ok.one", "dl.ok.two"), dead.events)
        assertTrue(errors.any { it.message == "dl down" })
        assertEquals(3L, report.droppedByReason[DropReason.MISSING_BINDING])
    }

    @Test
    fun `dead-letter flush failures are reported`() {
        val errors = CopyOnWriteArrayList<Throwable>()
        val logger = ILogger.create {
            deadLetter(FlushFailingDeadLetterSink())
            onDispatchError = { _, _, error -> errors += error }
        }

        val report = logger.closeAndReport()

        assertFalse(report.timedOut)
        assertTrue(report.sinkFailures.isEmpty())
        assertTrue(errors.any { it.message == "dl flush boom" })
    }

    @Test
    fun `dead-letter close failures are appended to sink failures`() {
        val logger = ILogger.create {
            deadLetter(CloseFailingDeadLetterSink())
        }

        val report = logger.closeAndReport()

        assertEquals(1, report.sinkFailures.size)
        assertTrue(report.sinkFailures.single().sink.contains("CloseFailingDeadLetterSink"))
        assertEquals("dl close boom", report.sinkFailures.single().error.message)
    }

    @Test
    fun `dead-letter queue overflow discards newest drops and reports them`() {
        val dead = BlockingDeadLetterSink()
        val errors = CopyOnWriteArrayList<Throwable>()
        val logger = ILogger.create {
            // Dead-letter capacity is max(16, bufferSize / 4).
            bufferSize = 4
            closeTimeoutMillis = 100
            deadLetter(dead)
            onDispatchError = { _, _, error -> errors += error }
        }

        logger.recordNow("dl.running")
        assertTrue(dead.entered.await(5, TimeUnit.SECONDS))
        repeat(16) { logger.recordNow("dl.fill.$it") }
        logger.recordNow("dl.overflow")

        assertTrue(
            "expected a full-queue report, got ${errors.map { it.message }}",
            errors.any { it.message?.contains("dead-letter queue is full") == true },
        )
        logger.close()
    }

    @Test
    fun `cancelled dead-letter worker reports its in-flight batch and queued remainder`() = runBlocking {
        val dead = BlockingDeadLetterSink()
        val errors = CopyOnWriteArrayList<Throwable>()
        val logger = ILogger.create {
            bufferSize = 4
            closeTimeoutMillis = 100
            deadLetter(dead)
            onDispatchError = { _, _, error -> errors += error }
        }

        logger.recordNow("dl.first")
        assertTrue(dead.entered.await(5, TimeUnit.SECONDS))
        logger.recordNow("dl.second")
        logger.recordNow("dl.third")
        logger.recordNow("dl.fourth")

        val report = logger.closeAndReport()

        assertTrue(report.timedOut)
        assertEquals(4L, report.droppedByReason[DropReason.MISSING_BINDING])
        assertTrue(
            "expected four lost-during-shutdown reports, got ${errors.map { it.message }}",
            awaitUntil(2_000) {
                errors.count { it.message?.contains("lost during shutdown") == true } == 4
            },
        )
    }
}
