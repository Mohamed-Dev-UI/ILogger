package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure-mode coverage: a broken sink must never break producers, close must
 * report failures instead of throwing, and reentrant sinks must terminate.
 */
class ChaosSinkTest {

    @Test
    fun `sync recording survives a permanently failing sink`() {
        val drops = Collections.synchronizedList(ArrayList<DroppedEvent>())
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val sink = FailingSink()
        val log = ILogger.create {
            sink(sink)
            retryMaxAttempts = 1
            retryBaseBackoffMillis = 0
            onDrop = { drops.add(it) }
            onDispatchError = { _, _, error -> errors.add(error) }
        }

        log.recordNow(action = "chaos.sync", level = LogLevel.AUDIT, dispatch = DispatchMode.INLINE)

        assertTrue("the sink was attempted", sink.attempts.get() >= 1)
        assertFalse("the event is reported as lost", drops.isEmpty())
        assertFalse("the failure is reported", errors.isEmpty())
        log.close()
    }

    @Test
    fun `suspend recording survives a permanently failing sink`() = runBlocking {
        val drops = Collections.synchronizedList(ArrayList<DroppedEvent>())
        val log = ILogger.create {
            sink(FailingSink(AssertionError("hard failure")))
            dispatch = DispatchMode.INLINE
            retryMaxAttempts = 1
            retryBaseBackoffMillis = 0
            onDrop = { drops.add(it) }
        }

        log.record(action = "chaos.suspend", level = LogLevel.ERROR)

        assertFalse(drops.isEmpty())
        log.close()
    }

    @Test
    fun `close reports a failing sink without throwing`() {
        val log = ILogger.create {
            sink(FailingSink())
            sink(CloseFailingSink())
        }

        val report = log.closeAndReport()

        assertFalse(report.timedOut)
        assertEquals(1, report.sinkFailures.size)
        assertEquals("cannot close", report.sinkFailures.single().error.message)
    }

    @Test
    fun `concurrent failing writes never escape to producers`() {
        val drops = Collections.synchronizedList(ArrayList<DroppedEvent>())
        val log = ILogger.create {
            sink(FailingSink())
            dispatch = DispatchMode.INLINE
            retryMaxAttempts = 1
            retryBaseBackoffMillis = 0
            onDrop = { drops.add(it) }
        }

        val threads = 6
        val perThread = 20
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            thread {
                start.await()
                repeat(perThread) { index ->
                    log.recordNow(action = "chaos.concurrent.$index", dispatch = DispatchMode.INLINE)
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))

        assertEquals(threads * perThread, drops.size)
        log.close()
    }

    @Test
    fun `reentrant inline sink is bounded and reports recursion`() {
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val sink = ReentrantSink()
        val log = ILogger.create {
            sink(sink)
            retryMaxAttempts = 1
            retryBaseBackoffMillis = 0
            onDispatchError = { _, _, error -> errors.add(error) }
        }
        sink.logger = log

        log.recordNow(action = "chaos.reentrant", dispatch = DispatchMode.INLINE)

        val writes = sink.writes.get()
        assertTrue("recursion must stay bounded, was $writes", writes in 1..32)
        assertTrue(
            "expected a recursive-dispatch report, got $errors",
            errors.any { it.message?.contains("recursive dispatch") == true },
        )
        log.close()
    }
}
