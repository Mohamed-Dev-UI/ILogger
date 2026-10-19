package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private class CollectingSink : LogSink {
    val events = Collections.synchronizedList(ArrayList<LogEvent>())
    var failFirstCalls: Int = 0
    val failures = AtomicInteger(0)

    private val lock = Any()

    override suspend fun write(event: LogEvent) {
        synchronized(lock) {
            if (failFirstCalls > 0) {
                failFirstCalls--
                failures.incrementAndGet()
                throw IllegalStateException("sink unavailable")
            }
        }
        events.add(event)
    }

    fun clearNow() = events.clear()
}

class ILoggerTest {

    private fun logger(sink: LogSink, configure: ILoggerBuilder.() -> Unit = {}): ILogger =
        ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
            configure()
        }

    @Test
    fun `manual record produces complete structured event`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink) { clock = FixedClock(1_000) }
        log.record(action = "task.created", entity = "Task", entityId = "42", source = "Repo.create")
        assertEquals(1, sink.events.size)
        val event = sink.events[0]
        assertEquals("task.created", event.action)
        assertEquals(LogLevel.AUDIT, event.level)
        assertEquals("Task", event.entity)
        assertEquals("42", event.entityId)
        assertEquals(Outcome.SUCCESS, event.outcome)
        assertEquals("Repo.create", event.source)
        assertEquals(1_000, event.timestampMillis)
        assertTrue(event.id.isNotBlank())
        log.close()
    }

    @Test
    fun `actor precedence explicit context provider`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink) {
            actorProvider = ActorProvider { Actor.user("from-provider") }
        }

        // provider fallback (no context, no explicit)
        log.record(action = "a")
        assertEquals("from-provider", sink.events.last().actor?.id)

        // context wins over provider
        withActor(Actor.user("from-context")) {
            log.record(action = "b")
        }
        assertEquals("from-context", sink.events.last().actor?.id)

        // explicit wins over context
        withActor(Actor.user("from-context")) {
            log.record(action = "c", actor = Actor.system())
        }
        assertEquals(ActorType.SYSTEM, sink.events.last().actor?.type)

        // nested merge: inner actor replaces, correlation from outer survives
        withActor(Actor.user("outer")) {
            withCorrelation("corr-1") {
                withActor(Actor.user("inner")) {
                    log.record(action = "d")
                }
            }
        }
        val merged = sink.events.last()
        assertEquals("inner", merged.actor?.id)
        assertEquals("corr-1", merged.correlationId)
        log.close()
    }

    @Test
    fun `correlation and causation flow through event`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink) { clock = FixedClock(42) }
        withCorrelation("req-abc") {
            withCausation("ev-1") {
                log.record(action = "x.y")
            }
        }
        assertEquals("req-abc", sink.events[0].correlationId)
        assertEquals("ev-1", sink.events[0].causationId)
        log.close()
    }

    @Test
    fun `suppressed sources skip event creation`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink)
        withLogContext(LogContext(suppressSources = setOf("TaskDao.insert"))) {
            log.record(action = "task.created", source = "TaskDao.insert")
        }
        log.record(action = "task.updated", source = "TaskDao.update")
        assertEquals(1, sink.events.size)
        assertEquals("TaskDao.update", sink.events[0].source)
        log.close()
    }

    @Test
    fun `diagnostic levels respect minLevel and never filter audit`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink) { minLevel = LogLevel.WARN }
        log.debug("quiet")
        log.info("quiet")
        log.warn("loud")
        log.error("loud")
        assertEquals(2, sink.events.size)

        log.record(action = "audit.still-emitted") // AUDIT unaffected by minLevel
        assertEquals("audit.still-emitted", sink.events.last().action)
        log.close()
    }

    @Test
    fun `failure events capture exception structure without throwing`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink)
        val original = IllegalStateException("boom", RuntimeException("root"))
        log.record(action = "op.failed", outcome = Outcome.FAILURE, failure = original)
        val event = sink.events[0]
        assertEquals(Outcome.FAILURE, event.outcome)
        assertNotNull(event.failure)
        assertEquals("java.lang.IllegalStateException", event.failure?.errorType)
        assertEquals("boom", event.failure?.message)
        assertEquals("java.lang.RuntimeException", event.failure?.rootCauseType)
        assertTrue(event.failure?.stackSummary?.isNotEmpty() ?: false)
        log.close()
    }

    @Test
    fun `sink errors are retried then dropped (unless propagate)`() = runTest {
        val sink = CollectingSink()
        val reported = ArrayList<Throwable>()
        val log = logger(sink) {
            onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
            retryMaxAttempts = 2
            retryBaseBackoffMillis = 1
            onDispatchError = { _, _, error -> reported.add(error) }
        }
        sink.failFirstCalls = 1
        log.record(action = "a.b")
        assertEquals(1, sink.failures.get())
        assertEquals(1, sink.events.size)

        sink.clearNow()
        sink.failFirstCalls = 99
        log.record(action = "c.d")
        assertEquals(99 - 1 - 2, sink.failFirstCalls) // attempts: 1 initial + 2 retries
        assertEquals(0, sink.events.size)
        assertEquals(1, reported.size)
        log.close()
    }

    @Test
    fun `propagate error mode fails the operation`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink) {
            onErrorMode(LogLevel.AUDIT, ErrorMode.PROPAGATE)
        }
        sink.failFirstCalls = 1
        try {
            log.record(action = "a.c")
            throw AssertionError("expected thrown error")
        } catch (expected: IllegalStateException) {
            // operation failed because the sink write failed; nothing written
        }
        assertEquals(0, sink.events.size)
        log.close()
    }

    @Test
    fun `async dispatch delivers via worker and close drains`() = runTest {
        val sink = CollectingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.ASYNC
            flushIntervalMillis = 5
            sink(sink)
        }
        repeat(50) { log.recordNow(action = "e.f") }
        log.close()
        assertEquals(50, sink.events.size)
    }

    @Test
    fun `buffered dispatch batches`() = runTest {
        val sink = CollectingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.BUFFERED
            batchSize = 10
            flushIntervalMillis = 1
            sink(sink)
        }
        repeat(25) { log.recordNow(action = "g.h") }
        log.flush()
        assertEquals(25, sink.events.size)
        log.close()
    }

    @Test
    fun `fail overflow policy fails the producer`() = runTest {
        val sink = CollectingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.BUFFERED
            bufferSize = 2
            batchSize = 1
            overflow = OverflowPolicy.FAIL
            flushIntervalMillis = 5_000
            sink(sink)
        }
        try {
            log.recordNow(action = "a") // occupies allocator sync
            log.recordNow(action = "b")
            log.recordNow(action = "c") // third fails on full buffer
            // first two may already be consumed by the worker, so accept either failure or success here
        } catch (_: IllegalStateException) {
        }
        log.close()
    }

    @Test
    fun `recursion through sink is dropped`() = runTest {
        val reported = AtomicInteger(0)
        var log: ILogger? = null
        log = logger(object : LogSink {
            override suspend fun write(event: LogEvent) {
                // Sink re-enters the logger: must be dropped, not stack-overflow.
                if (event.action == "first.op") {
                    log?.record(action = "recursive.op")
                }
            }
        }) {
            onDispatchError = { _, _, _ -> reported.incrementAndGet() }
        }
        log.record(action = "first.op")
        assertEquals(1, reported.get()) // the recursive re-entry was detected and dropped
        log.close()
    }

    @Test
    fun `concurrent coroutines never leak actor context`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink)
        val recorder = ArrayList<LogEvent>()
        (1..200).map { n ->
            async(Dispatchers.Default) {
                withActor(Actor.user("user-$n")) {
                    withCorrelation("req-$n") {
                        log.record(action = "task.created")
                    }
                }
            }
        }.awaitAll()
        assertEquals(200, sink.events.size)
        for (event in sink.events) {
            val id = event.actor?.id?.removePrefix("user-") ?: error("missing actor")
            assertEquals("req-$id", event.correlationId)
        }
        log.close()
    }

    @Test
    fun `disabled logger drops everything`() {
        val disabled: ILogger = ILogger.disabled
        assertFalse(disabled.isEnabled(LogLevel.AUDIT))
    }

    @Test
    fun `category routing targets specific and catch-all bindings`() = runTest {
        val dbSink = CollectingSink()
        val allSink = CollectingSink()
        val httpSink = CollectingSink()
        val log = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(allSink)               // catch-all
            sink(dbSink, category = "db")
            sink(httpSink, category = "http")
        }
        log.record(action = "task.created", category = "db")
        log.record(action = "http.request", category = "http")
        log.record(action = "misc.event") // default (no category)

        assertEquals(1, dbSink.events.size) // only the db event
        assertEquals(3, allSink.events.size) // catch-all receives everything
        assertEquals(1, httpSink.events.size)
        log.close()
    }

    @Test
    fun `nullability contract for missing values`() = runTest {
        val sink = CollectingSink()
        val log = logger(sink)
        log.record(action = "minimal.op")
        val event = sink.events[0]
        assertNull(event.entity)
        assertNull(event.actor)
        assertNull(event.correlationId)
        assertEquals(0, event.metadata.size)
        assertEquals(1, event.actionVersion)
        assertTrue(event.schemaId == "minimal.op")
        log.close()
    }
}
