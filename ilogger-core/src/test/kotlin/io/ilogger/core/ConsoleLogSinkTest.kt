package io.ilogger.core

import io.ilogger.annotations.LogLevel
import io.ilogger.core.sink.ConsoleLogSink
import io.ilogger.core.sink.DefaultTextEventCodec
import io.ilogger.core.sink.EventCodec
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleLogSinkTest {

    private fun event(id: String, action: String = "task.created") = LogEvent(
        id = id,
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.INFO,
        action = action,
        outcome = Outcome.SUCCESS,
    )

    @Test
    fun `custom codec output is written verbatim`() = runBlocking {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val codec = EventCodec { event -> "encoded:${event.action}" }
        val sink = ConsoleLogSink(codec) { line -> lines.add(line) }

        sink.write(event("e1", "task.created"))
        sink.writeDirect(event("e2", "task.deleted"))

        assertEquals(listOf("encoded:task.created", "encoded:task.deleted"), lines)
    }

    @Test
    fun `default codec renders identity and outcome`() {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val sink = ConsoleLogSink(DefaultTextEventCodec) { line -> lines.add(line) }

        sink.writeDirect(
            LogEvent(
                id = "e1",
                timestampMillis = 10,
                level = LogLevel.WARN,
                action = "task.failed",
                entity = "Task",
                entityId = "42",
                outcome = Outcome.FAILURE,
                failure = LogFailure.from(IllegalStateException("boom")),
            ),
        )

        val line = lines.single()
        assertTrue(line, line.contains("10 [WARN] task.failed (failure)"))
        assertTrue(line, line.contains("Task(42)"))
        assertTrue(line, line.contains("failure{java.lang.IllegalStateException: boom}"))
    }

    @Test
    fun `concurrent writes are serialized into whole lines`() {
        val lines = Collections.synchronizedList(ArrayList<String>())
        // Deliberately slow, non-atomic writer: without the sink's lock, lines
        // from different threads would interleave.
        val sink = ConsoleLogSink(EventCodec { "line:${it.id}" }) { line ->
            for (character in line) {
                Thread.yield()
                synchronized(lines) { lines.add(character.toString()) }
            }
            synchronized(lines) { lines.add("\n") }
        }

        val threads = 8
        val perThread = 25
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { threadIndex ->
            thread {
                start.await()
                repeat(perThread) { index -> sink.writeDirect(event("t$threadIndex-$index")) }
                done.countDown()
            }
        }
        start.countDown()
        // Generous bound: the writer yields per character on purpose, and a
        // loaded CI box can take far longer than the lock itself ever would.
        assertTrue(done.await(60, TimeUnit.SECONDS))

        val joined = synchronized(lines) { lines.joinToString("") }
        val rendered = joined.split('\n').filter { it.isNotBlank() }
        assertEquals(threads * perThread, rendered.size)
        assertTrue(rendered.all { it.matches(Regex("line:t\\d+-\\d+")) })
        assertEquals(threads * perThread, rendered.toSet().size)
    }

    @Test
    fun `writer failures propagate to the caller`() {
        val sink = ConsoleLogSink(EventCodec { "x" }) { throw IllegalStateException("writer down") }

        assertThrows(IllegalStateException::class.java) {
            sink.writeDirect(event("e1"))
        }
    }
}
