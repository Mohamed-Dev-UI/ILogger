package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.annotations.OnCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAdaptersTest {

    private fun logger(sink: DirectLogSink): ILogger =
        ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
        }

    private fun loggerOnTestScheduler(sink: DirectLogSink, scheduler: TestCoroutineScheduler): ILogger =
        ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
            workerDispatcher = StandardTestDispatcher(scheduler)
        }

    private fun outcomeEvents(sink: RecordingSink): List<Pair<String, Outcome>> =
        sink.events.map { it.action to it.outcome }

    @Test
    fun `loggedFlow emits start and success on normal completion`() = runTest {
        val sink = RecordingSink()
        val log = logger(sink)
        log.use {
            val values = flowOf(1, 2, 3).loggedFlow(
                log,
                action = "batch.drained",
                entity = "Batch",
                source = "StreamAdaptersTest",
            ).toList()

            assertEquals(listOf(1, 2, 3), values)
            assertEquals(
                listOf("batch.drained" to Outcome.START, "batch.drained" to Outcome.SUCCESS),
                outcomeEvents(sink),
            )
            val success = sink.events[1]
            assertEquals("Batch", success.entity)
            assertEquals("StreamAdaptersTest", success.source)
            assertEquals(LogLevel.INFO, success.level)
        }
    }

    @Test
    fun `loggedFlow emits failure and rethrows the original error`() = runTest {
        val sink = RecordingSink()
        val log = logger(sink)
        log.use {
            val original = IllegalStateException("stream broke")
            val upstream = flow<Int> {
                emit(1)
                throw original
            }
            val thrown = runCatching {
                upstream.loggedFlow(log, action = "batch.drained").toList()
            }.exceptionOrNull()

            assertSame(original, thrown)
            assertTrue(thrown!!.suppressed.isEmpty())
            assertEquals(
                listOf("batch.drained" to Outcome.START, "batch.drained" to Outcome.FAILURE),
                outcomeEvents(sink),
            )
            assertEquals("stream broke", sink.events[1].failure?.message)
        }
    }

    @Test
    fun `loggedFlow skips cancellation events by default and logs them on opt-in`() = runTest {
        val sink = RecordingSink()
        val log = loggerOnTestScheduler(sink, testScheduler)
        log.use {
            val upstream = flow {
                emit(1)
                awaitCancellation()
            }

            val skipped = launch(start = CoroutineStart.UNDISPATCHED) {
                upstream.loggedFlow(log, action = "batch.drained").toList()
            }
            skipped.cancelAndJoin()
            // Only the subscription event: cancellation itself is skipped.
            assertEquals(1, sink.events.size)
            assertEquals(Outcome.START, sink.events[0].outcome)

            val optedIn = launch(start = CoroutineStart.UNDISPATCHED) {
                upstream.loggedFlow(
                    log,
                    action = "batch.drained",
                    onCancellation = OnCancellation.LOG,
                ).toList()
            }
            optedIn.cancelAndJoin()
            assertTrue(awaitUntil { sink.events.size == 3 })
            val event = sink.events[2]
            assertEquals(Outcome.FAILURE, event.outcome)
            assertTrue(event.failure?.cancelled == true)
        }
    }

    @Test
    fun `loggedChannel emits start and success and fails on error`() = runTest {
        val sink = RecordingSink()
        val log = logger(sink)
        log.use {
            val channel = Channel<Int>(capacity = 4)
            launch {
                channel.send(1)
                channel.send(2)
                channel.close()
            }
            assertEquals(listOf(1, 2), channel.loggedChannel(log, action = "channel.drained").toList())
            assertEquals(
                listOf("channel.drained" to Outcome.START, "channel.drained" to Outcome.SUCCESS),
                outcomeEvents(sink),
            )

            val failing = Channel<Int>(capacity = 1)
            launch {
                failing.send(1)
                failing.close(IllegalStateException("channel fault"))
            }
            val thrown = runCatching {
                failing.loggedChannel(log, action = "channel.drained").toList()
            }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
            assertEquals("channel fault", thrown!!.message)
            assertEquals(Outcome.FAILURE, sink.events.last().outcome)
        }
    }

    @Test
    fun `loggedSequence emits start success and failure`() {
        val sink = RecordingSink()
        val log = logger(sink)
        log.use {
            val values = listOf("a", "b").asSequence()
                .loggedSequence(log, action = "seq.read", entityId = "seq-1", dispatch = DispatchMode.INLINE)
                .toList()
            assertEquals(listOf("a", "b"), values)
            assertEquals(
                listOf("seq.read" to Outcome.START, "seq.read" to Outcome.SUCCESS),
                outcomeEvents(sink),
            )
            assertEquals("seq-1", sink.events[1].entityId)

            val failure = runCatching {
                sequence {
                    yield("x")
                    throw IllegalStateException("seq broke")
                }.loggedSequence(log, action = "seq.read", dispatch = DispatchMode.INLINE).toList()
            }.exceptionOrNull()
            assertEquals("seq broke", failure!!.message)
            assertEquals(Outcome.FAILURE, sink.events.last().outcome)
        }
    }

    @Test
    fun `adapters respect the enabled fast path`() = runTest {
        val sink = RecordingSink()
        val log = logger(sink)
        log.use {
            log.enabled = false
            assertEquals(listOf(1), flowOf(1).loggedFlow(log, action = "batch.drained").toList())
            assertEquals(listOf(1), listOf(1).asSequence().loggedSequence(log, action = "seq.read").toList())
            assertTrue(sink.events.isEmpty())
        }
    }
}
