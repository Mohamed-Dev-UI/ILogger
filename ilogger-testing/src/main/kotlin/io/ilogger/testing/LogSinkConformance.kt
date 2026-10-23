package io.ilogger.testing

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.DirectLogSink
import io.ilogger.core.FieldChange
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.Outcome
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared contract tests for [LogSink] implementations.
 *
 * Adopt the suite in the sink's own module:
 *
 * ```kotlin
 * class MySinkConformanceTest : LogSinkConformance() {
 *     override fun createSink(): LogSink = MySink(...)
 *
 *     override fun deliveredIds(sink: LogSink): List<String> =
 *         (sink as MySink).readBack().map { it.id }
 * }
 * ```
 *
 * [createSink] must return a fresh sink per test. [deliveredIds] is called
 * after [LogSink.flush] and must read every event written since creation, in
 * delivery order for single-threaded tests. [deliveredEvents] is optional: return
 * empty when the destination is not losslessly readable (for example a text
 * file with a human-readable codec), which skips content-equality assertions.
 *
 * The suite covers: single writes, batch writes and their order, empty
 * batches, flush and close idempotency, data retention across close, equal
 * timestamps, the [DirectLogSink] fast path, and concurrent writers without
 * loss or duplication.
 *
 * Sinks that intentionally filter, sample or aggregate events are not suitable
 * for this suite — test their policy separately, then run conformance on the
 * surviving destination.
 *
 * Test methods use camelCase rather than backticked sentences: this class ships
 * in `ilogger-testing`, which Android module tests dex, and D8 rejects spaces
 * in member names below DEX version 040 (minSdk < 34).
 */
public abstract class LogSinkConformance {

    /** Creates a fresh sink for the current test. */
    protected abstract fun createSink(): LogSink

    /**
     * Reads back the ids of all events the sink accepted, in write order.
     * Called after [LogSink.flush]; may open files or query databases.
     */
    protected abstract fun deliveredIds(sink: LogSink): List<String>

    /**
     * Optional lossless read-back used for content assertions. The default
     * returns empty, disabling those assertions.
     */
    protected open fun deliveredEvents(sink: LogSink): List<LogEvent> = emptyList()

    /** Releases the sink after a test; failures are ignored. Default: close. */
    protected open fun dispose(sink: LogSink) {
        try {
            sink.close()
        } catch (_: Throwable) {
        }
    }

    /** Deterministic, fully populated event used by the suite. */
    protected fun sample(index: Int, timestampMillis: Long = BASE_TIMESTAMP + index): LogEvent = LogEvent(
        id = "conformance-$index",
        timestampMillis = timestampMillis,
        level = LogLevel.AUDIT,
        action = "conformance.event.$index",
        actionVersion = 2,
        actor = Actor.user("actor-$index", name = "Actor $index"),
        entity = "Conformance",
        entityId = index.toString(),
        metadata = mapOf("index" to index, "note" to "event $index"),
        changes = listOf(FieldChange(field = "note", before = "before-$index", after = "after-$index")),
        source = "LogSinkConformance",
        correlationId = "correlation-$index",
        outcome = Outcome.SUCCESS,
    )

    @Test(timeout = 30_000)
    public fun writeDeliversOneEventAndPreservesContent() {
        runSink { sink ->
            val event = sample(1)
            sink.write(event)
            sink.flush()

            assertEquals(listOf(event.id), deliveredIds(sink))
            val events = deliveredEvents(sink)
            if (events.isNotEmpty()) {
                assertEquals(1, events.size)
                assertSameEvent(event, events.single())
            }
        }
    }

    @Test(timeout = 30_000)
    public fun writeAllDeliversEveryEventInOrder() {
        runSink { sink ->
            val events = List(5) { sample(it) }
            sink.writeAll(events)
            sink.flush()

            assertEquals(events.map { it.id }, deliveredIds(sink))
            val readBack = deliveredEvents(sink)
            if (readBack.isNotEmpty()) {
                assertEquals(events.size, readBack.size)
                events.zip(readBack).forEach { (expected, actual) -> assertSameEvent(expected, actual) }
            }
        }
    }

    @Test(timeout = 30_000)
    public fun writeAllWithEmptyListIsNoOp() {
        runSink { sink ->
            sink.writeAll(emptyList())
            sink.flush()

            assertTrue(deliveredIds(sink).isEmpty())
        }
    }

    @Test(timeout = 30_000)
    public fun flushIsIdempotentAndSafeWithoutEvents() {
        runSink { sink ->
            sink.flush()
            sink.flush()

            val event = sample(1)
            sink.write(event)
            sink.flush()
            sink.flush()
            sink.flush()

            assertEquals(listOf(event.id), deliveredIds(sink))
        }
    }

    @Test(timeout = 30_000)
    public fun closeIsIdempotentAndRetainsFlushedEvents() {
        runSink { sink ->
            val event = sample(1)
            sink.write(event)
            sink.flush()

            sink.close()
            sink.close()

            assertEquals(listOf(event.id), deliveredIds(sink))
        }
    }

    @Test(timeout = 30_000)
    public fun eventsWithIdenticalTimestampsAreAllDelivered() {
        runSink { sink ->
            val events = List(3) { sample(it, timestampMillis = 1_700_000_000_000) }
            sink.writeAll(events)
            sink.flush()

            assertEquals(events.map { it.id }, deliveredIds(sink))
        }
    }

    @Test(timeout = 30_000)
    public fun directFastPathRecordsTheSameWayAsWrite() {
        runSink { sink ->
            if (sink !is DirectLogSink) return@runSink

            val event = sample(1)
            sink.writeDirect(event)
            sink.flush()

            assertEquals(listOf(event.id), deliveredIds(sink))
        }
    }

    @Test(timeout = 60_000)
    public fun concurrentWritersLoseAndDuplicateNothing() {
        runSink { sink ->
            val threads = 6
            val perThread = 20
            val events = List(threads * perThread) { sample(it) }
            val errors = Collections.synchronizedList(ArrayList<Throwable>())
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)

            repeat(threads) { worker ->
                thread(name = "conformance-writer-$worker") {
                    try {
                        start.await()
                        runBlocking {
                            repeat(perThread) { index ->
                                sink.write(events[worker * perThread + index])
                            }
                        }
                    } catch (error: Throwable) {
                        errors.add(error)
                    } finally {
                        done.countDown()
                    }
                }
            }

            start.countDown()
            assertTrue("writers did not finish", done.await(30, TimeUnit.SECONDS))
            assertTrue("writers failed: $errors", errors.isEmpty())

            sink.flush()
            val delivered = deliveredIds(sink)
            assertEquals("delivered count", events.size, delivered.size)
            assertEquals("delivered ids", events.map { it.id }.sorted(), delivered.sorted())
        }
    }

    private fun runSink(block: suspend (LogSink) -> Unit) {
        val sink = createSink()
        try {
            runBlocking { block(sink) }
        } finally {
            dispose(sink)
        }
    }

    private fun assertSameEvent(expected: LogEvent, actual: LogEvent) {
        assertEquals("id", expected.id, actual.id)
        assertEquals("timestampMillis", expected.timestampMillis, actual.timestampMillis)
        assertEquals("level", expected.level, actual.level)
        assertEquals("action", expected.action, actual.action)
        assertEquals("actionVersion", expected.actionVersion, actual.actionVersion)
        assertEquals("actor", expected.actor, actual.actor)
        assertEquals("entity", expected.entity, actual.entity)
        assertEquals("entityId", expected.entityId, actual.entityId)
        assertEquals("metadata", expected.metadata, actual.metadata)
        assertEquals("changes", expected.changes, actual.changes)
        assertEquals("source", expected.source, actual.source)
        assertEquals("category", expected.category, actual.category)
        assertEquals("correlationId", expected.correlationId, actual.correlationId)
        assertEquals("causationId", expected.causationId, actual.causationId)
        assertEquals("outcome", expected.outcome, actual.outcome)
        assertEquals("failure", expected.failure, actual.failure)
    }

    private companion object {
        const val BASE_TIMESTAMP: Long = 1_700_000_000_000
    }
}
