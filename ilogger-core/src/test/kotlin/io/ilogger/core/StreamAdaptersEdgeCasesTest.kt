package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.annotations.OnCancellation
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAdaptersEdgeCasesTest {

    private class TerminalFailingSink : LogSink {
        val events = Collections.synchronizedList(ArrayList<LogEvent>())
        val failureAttempts = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun write(event: LogEvent) {
            if (event.outcome == Outcome.FAILURE) {
                failureAttempts.incrementAndGet()
                throw IllegalStateException("terminal sink down")
            }
            events += event
        }
    }

    private fun logger(sink: LogSink): ILogger = ILogger.create {
        dispatch = DispatchMode.INLINE
        sink(sink)
        onErrorMode(LogLevel.INFO, ErrorMode.PROPAGATE)
    }

    @Test
    fun `flow failure keeps the original error and suppresses the sink failure`() = runTest {
        val sink = TerminalFailingSink()
        logger(sink).use { log ->
            val original = IllegalStateException("stream broke")
            val thrown = runCatching {
                flow<Int> {
                    emit(1)
                    throw original
                }.loggedFlow(log, action = "batch.drained").toList()
            }.exceptionOrNull()

            assertSame(original, thrown)
            assertEquals(1, thrown!!.suppressed.size)
            assertEquals("terminal sink down", thrown.suppressed[0].message)
            assertEquals(listOf(Outcome.START), sink.events.map { it.outcome })
        }
    }

    @Test
    fun `sequence hasNext failure is logged and the original error wins`() {
        val sink = TerminalFailingSink()
        logger(sink).use { log ->
            val failing = Sequence {
                object : Iterator<Int> {
                    override fun hasNext(): Boolean = throw IllegalStateException("hasNext broke")

                    override fun next(): Int = 1
                }
            }
            val thrown = runCatching {
                failing.loggedSequence(log, action = "seq.read", dispatch = DispatchMode.INLINE).toList()
            }.exceptionOrNull()

            assertEquals("hasNext broke", thrown!!.message)
            assertEquals(1, thrown.suppressed.size)
            assertEquals("terminal sink down", thrown.suppressed[0].message)
            assertEquals(1, sink.failureAttempts.get())
            assertEquals(listOf(Outcome.START), sink.events.map { it.outcome })
        }
    }

    @Test
    fun `sequence next failure is logged and the original error wins`() {
        val sink = TerminalFailingSink()
        logger(sink).use { log ->
            val failing = Sequence {
                object : Iterator<Int> {
                    override fun hasNext(): Boolean = true

                    override fun next(): Int = throw IllegalStateException("next broke")
                }
            }
            val thrown = runCatching {
                failing.loggedSequence(log, action = "seq.read", dispatch = DispatchMode.INLINE).toList()
            }.exceptionOrNull()

            assertEquals("next broke", thrown!!.message)
            assertEquals(1, thrown.suppressed.size)
            assertEquals(1, sink.failureAttempts.get())
            assertEquals(listOf(Outcome.START), sink.events.map { it.outcome })
        }
    }

    @Test
    fun `sequence cancellation is skipped by default and logged on opt-in`() = runBlocking {
        val sink = RecordingSink()
        logger(sink).use { log ->
            val cancelling = Sequence {
                object : Iterator<Int> {
                    override fun hasNext(): Boolean = throw CancellationException("stop")

                    override fun next(): Int = 1
                }
            }

            runCatching {
                cancelling.loggedSequence(log, action = "seq.read", dispatch = DispatchMode.INLINE)
                    .toList()
            }
            assertEquals(listOf(Outcome.START), sink.events.map { it.outcome })

            runCatching {
                cancelling.loggedSequence(
                    log,
                    action = "seq.read",
                    dispatch = DispatchMode.INLINE,
                    onCancellation = OnCancellation.LOG,
                ).toList()
            }
            assertTrue(awaitUntil { sink.events.size == 3 })
            val failure = sink.events[2]
            assertEquals(Outcome.FAILURE, failure.outcome)
            assertTrue(failure.failure!!.cancelled)
        }
    }

    @Test
    fun `exhausted sequences are logged once even if next is called again`() {
        val sink = RecordingSink()
        logger(sink).use { log ->
            val iterator = listOf(1).asSequence()
                .loggedSequence(log, action = "seq.read", dispatch = DispatchMode.INLINE)
                .iterator()

            assertTrue(iterator.hasNext())
            assertEquals(1, iterator.next())
            assertFalse(iterator.hasNext())
            assertFalse(iterator.hasNext())
            runCatching { iterator.next() }

            assertEquals(
                listOf(Outcome.START, Outcome.SUCCESS),
                sink.events.map { it.outcome },
            )
        }
    }

    @Test
    fun `loggedFlow records every configured event field`() = runTest {
        val sink = RecordingSink()
        logger(sink).use { log ->
            flowOf(1).loggedFlow(
                log,
                action = "batch.drained",
                actionVersion = 3,
                level = LogLevel.WARN,
                entity = "Batch",
                entityId = "b-1",
                source = "StreamAdaptersEdgeCasesTest",
                category = "batch",
                metadata = mapOf("size" to 1),
                dispatch = DispatchMode.INLINE,
            ).toList()

            assertEquals(2, sink.events.size)
            for (event in sink.events) {
                assertEquals("batch.drained", event.action)
                assertEquals(3, event.actionVersion)
                assertEquals(LogLevel.WARN, event.level)
                assertEquals("Batch", event.entity)
                assertEquals("b-1", event.entityId)
                assertEquals("StreamAdaptersEdgeCasesTest", event.source)
                assertEquals("batch", event.category)
                assertEquals(1, event.metadata["size"])
            }
        }
    }

    @Test
    fun `loggedSequence records every configured event field`() {
        val sink = RecordingSink()
        logger(sink).use { log ->
            listOf(1).asSequence().loggedSequence(
                log,
                action = "seq.read",
                actionVersion = 2,
                level = LogLevel.DEBUG,
                entity = "Batch",
                entityId = "b-2",
                source = "StreamAdaptersEdgeCasesTest",
                category = "batch",
                metadata = mapOf("size" to 1),
                dispatch = DispatchMode.INLINE,
            ).toList()

            assertEquals(2, sink.events.size)
            val success = sink.events[1]
            assertEquals("seq.read", success.action)
            assertEquals(2, success.actionVersion)
            assertEquals(LogLevel.DEBUG, success.level)
            assertEquals("Batch", success.entity)
            assertEquals("b-2", success.entityId)
            assertEquals("StreamAdaptersEdgeCasesTest", success.source)
            assertEquals("batch", success.category)
            assertEquals(1, success.metadata["size"])
        }
    }
}
