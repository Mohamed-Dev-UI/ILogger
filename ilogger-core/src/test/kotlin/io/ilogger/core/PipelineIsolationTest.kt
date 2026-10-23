package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class PipelineIsolationTest {

    @Test
    fun `a slow blocking sink cannot delay a fast sink`() = runBlocking {
        val slow = BlockingSink()
        val fast = RecordingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sink(slow)
            sink(fast)
        }
        try {
            repeat(5) { log.record(action = "event.$it") }
            assertTrue(
                "fast sink should receive every event while the slow sink is blocked",
                awaitUntil(1_000) { fast.count() == 5 },
            )
            assertEquals(0, slow.count())
        } finally {
            slow.release.countDown()
            log.close()
        }
    }

    @Test
    fun `retries in one binding do not stall another binding`() = runBlocking {
        val attempts = AtomicInteger()
        val failing = object : LogSink {
            override suspend fun write(event: LogEvent) {
                attempts.incrementAndGet()
                throw IllegalStateException("sink unavailable")
            }
        }
        val fast = RecordingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
            retryMaxAttempts = 2
            retryBaseBackoffMillis = 300
            sink(failing)
            sink(fast)
        }
        try {
            log.record(action = "event")
            assertTrue(awaitUntil(500) { fast.count() == 1 })
            assertTrue(
                "the failing binding is still in its retry cycle, attempts=${attempts.get()}",
                attempts.get() in 1..2,
            )
        } finally {
            log.close()
        }
    }

    @Test
    fun `per sink ordering is preserved`() = runBlocking {
        val sink = RecordingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sink(sink)
        }
        repeat(100) { log.record(action = "a.$it") }
        assertTrue(awaitUntil(5_000) { sink.count() == 100 })
        assertEquals((0 until 100).map { "a.$it" }, sink.actions())
        log.close()
    }

    @Test
    fun `memory stays bounded per binding under a stalled sink`() = runBlocking {
        val slow = BlockingSink()
        val fast = RecordingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 4
            overflow = OverflowPolicy.DROP_NEWEST
            sink(slow)
            sink(fast)
        }
        try {
            repeat(200) { log.record(action = "e.$it") }

            val stats = log.stats()
            // Two bindings, each capped at bufferSize events.
            assertTrue("pending must stay bounded, was ${stats.pending}", stats.pending <= 8)
            assertTrue("stats=$stats", (stats.droppedByReason[DropReason.OVERFLOW_NEWEST] ?: 0) > 0)
            assertEquals("the stalled sink must not receive events while blocked", 0, slow.count())
        } finally {
            slow.release.countDown()
            log.close()
        }
    }

    @Test
    fun `a sink throwing CancellationException does not kill its pipeline`() = runBlocking {
        val captured = Collections.synchronizedList(ArrayList<LogEvent>())
        val sink = object : LogSink {
            override suspend fun write(event: LogEvent) {
                if (event.action == "cancel") throw CancellationException("sink cancelled itself")
                captured.add(event)
            }
        }
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            onErrorMode(LogLevel.AUDIT, ErrorMode.DROP)
            sink(sink)
        }
        log.record(action = "cancel")
        log.record(action = "after")
        assertTrue(awaitUntil(2_000) { captured.size == 1 })
        assertEquals("after", captured[0].action)
        assertEquals(1L, log.stats().droppedByReason[DropReason.SINK_FAILURE])
        log.close()
    }
}
