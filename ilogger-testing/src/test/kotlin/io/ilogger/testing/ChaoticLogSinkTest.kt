package io.ilogger.testing

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.DropReason
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaoticLogSinkTest {

    private fun event(index: Int): LogEvent =
        LogEvent(id = "chaos-$index", timestampMillis = 1_000, level = LogLevel.AUDIT, action = "chaos.$index")

    private fun failurePattern(seed: Long): List<Boolean> {
        val sink = ChaoticLogSink(InMemoryLogSink(), seed = seed, failureRate = 0.5, slowRate = 0.0)
        return runBlocking {
            (0 until 40).map { index -> runCatching { sink.write(event(index)) }.isFailure }
        }
    }

    @Test
    fun `the same seed produces the same failure pattern`() {
        val first = failurePattern(seed = 7)
        val second = failurePattern(seed = 7)

        assertEquals(first, second)
        assertTrue("the pattern should contain failures", first.any { it })
        assertTrue("the pattern should contain deliveries", first.any { !it })
    }

    @Test
    fun `engine accounting balances under seeded chaos`() {
        val delegate = InMemoryLogSink()
        val chaos = ChaoticLogSink(
            delegate = delegate,
            seed = 42,
            failureRate = 0.35,
            slowRate = 0.15,
            maxDelayMillis = 2,
        )
        val deadLetters = RecordingDeadLetterSink()
        val logger = ILogger.create {
            sink(chaos)
            deadLetter(deadLetters)
            dispatch = DispatchMode.INLINE
            retryMaxAttempts = 1
            retryBaseBackoffMillis = 0
        }

        repeat(100) { index ->
            logger.recordNow(action = "chaos.$index", level = LogLevel.AUDIT, dispatch = DispatchMode.INLINE)
        }
        val report = logger.closeAndReport()

        assertTrue("the seed must produce failures", chaos.failedCallCount() > 0)
        assertTrue("every event must reach the sink at least once", chaos.attemptedEventCount() >= 100)
        assertEquals(100, delegate.count() + deadLetters.count())
        assertEquals(delegate.count().toLong(), report.delivered)
        assertFalse(report.timedOut)
        assertTrue(
            "unexpected drop reasons: ${deadLetters.reasons()}",
            deadLetters.reasons().all { it == DropReason.SINK_FAILURE || it == DropReason.RETRY_EXHAUSTED },
        )
    }

    @Test
    fun `close failure is injected after the delegate closes`() {
        val delegate = FakeLogSink()
        val chaos = ChaoticLogSink(delegate, seed = 1, closeFailureRate = 1.0)

        val result = runCatching { chaos.close() }

        assertTrue(result.isFailure)
        assertEquals(1, delegate.closeCallCount())
    }

    @Test
    fun `configuration arguments are validated`() {
        val delegate = InMemoryLogSink()

        assertThrows(IllegalArgumentException::class.java) {
            ChaoticLogSink(delegate, failureRate = -0.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChaoticLogSink(delegate, slowRate = 1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChaoticLogSink(delegate, maxDelayMillis = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChaoticLogSink(delegate, closeFailureRate = 2.0)
        }
    }

    @Test
    fun `writeAll makes one decision for the whole batch`() = runBlocking {
        val delegate = FakeLogSink()
        val chaos = ChaoticLogSink(delegate, seed = 2, failureRate = 0.0, slowRate = 0.0)
        val batch = List(3) { event(it) }

        chaos.writeAll(batch)

        assertEquals(listOf(batch.map { it.id }), delegate.batches().map { events -> events.map { it.id } })
        assertEquals(1, chaos.callCount())
        assertEquals(3, chaos.attemptedEventCount())
        assertEquals(0, chaos.failedCallCount())
        assertEquals(0, chaos.delayedCallCount())

        chaos.write(event(9))

        assertEquals(2, chaos.callCount())
        assertEquals(4, chaos.attemptedEventCount())
        assertEquals(4, delegate.count())
    }

    @Test
    fun `empty batches are forwarded and do not count as chaos calls`() = runBlocking {
        val delegate = FakeLogSink()
        val chaos = ChaoticLogSink(delegate, seed = 3, failureRate = 1.0)

        chaos.writeAll(emptyList())

        assertEquals(1, delegate.writeAllCallCount())
        assertEquals(listOf(emptyList<LogEvent>()), delegate.batches())
        assertEquals(0, chaos.callCount())
        assertEquals(0, chaos.attemptedEventCount())
        assertEquals(0, chaos.failedCallCount())
    }

    @Test
    fun `a fully failing sink rejects single writes and batches`() = runBlocking {
        val delegate = InMemoryLogSink()
        val chaos = ChaoticLogSink(delegate, seed = 4, failureRate = 1.0)

        val single = runCatching { chaos.write(event(1)) }
        val batch = runCatching { chaos.writeAll(listOf(event(2), event(3))) }

        assertTrue(single.exceptionOrNull() is IOException)
        assertTrue(single.exceptionOrNull()!!.message!!.contains("simulated write failure"))
        assertTrue(batch.exceptionOrNull() is IOException)
        assertTrue(batch.exceptionOrNull()!!.message!!.contains("simulated batch failure"))
        assertEquals(2, chaos.failedCallCount())
        assertEquals(3, chaos.attemptedEventCount())
        assertEquals(0, delegate.count())
    }

    @Test
    fun `slow writes are counted and honour the maximum delay`() = runBlocking {
        val delegate = FakeLogSink()
        val chaos = ChaoticLogSink(
            delegate,
            seed = 5,
            failureRate = 0.0,
            slowRate = 1.0,
            maxDelayMillis = 1,
        )

        repeat(5) { chaos.write(event(it)) }

        assertEquals(5, chaos.delayedCallCount())
        assertEquals(5, delegate.count())
    }

    @Test
    fun `slow batches are delayed once for the whole batch`() = runBlocking {
        val delegate = FakeLogSink()
        val chaos = ChaoticLogSink(
            delegate,
            seed = 8,
            failureRate = 0.0,
            slowRate = 1.0,
            maxDelayMillis = 1,
        )

        chaos.writeAll(List(3) { event(it) })

        assertEquals(1, chaos.callCount())
        assertEquals(3, chaos.attemptedEventCount())
        assertEquals(1, chaos.delayedCallCount())
        assertEquals(3, delegate.count())
    }

    @Test
    fun `a zero maximum delay skips sleeping entirely`() = runBlocking {
        val delegate = FakeLogSink()
        val chaos = ChaoticLogSink(
            delegate,
            seed = 6,
            failureRate = 0.0,
            slowRate = 1.0,
            maxDelayMillis = 0,
        )

        chaos.write(event(0))

        assertEquals(0, chaos.delayedCallCount())
        assertEquals(1, delegate.count())
    }

    @Test
    fun `flush and successful close delegate`() = runBlocking {
        val delegate = FakeLogSink()
        val chaos = ChaoticLogSink(delegate, seed = 7, failureRate = 0.0, closeFailureRate = 0.0)

        chaos.flush()
        chaos.close()

        assertEquals(1, delegate.flushCallCount())
        assertEquals(1, delegate.closeCallCount())
    }
}
