package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private fun event(action: String, id: String = action): LogEvent = LogEvent(
    id = id,
    timestampMillis = 1_700_000_000_000,
    level = LogLevel.AUDIT,
    action = action,
)

class TriggerSinkTest {

    @Test
    fun `trigger sink forwards the whole batch in one call`() = runTest {
        val batches = mutableListOf<List<String>>()
        val sink = TriggerSink(onEvents = { events -> batches.add(events.map { it.action }) })

        sink.writeAll(listOf(event("a"), event("b"), event("c")))

        assertEquals(listOf(listOf("a", "b", "c")), batches)
    }

    @Test
    fun `onEvent receives every emitted event`() = runTest {
        val seen = mutableListOf<String>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onEvent { seen.add(it.action) }
        }

        logger.record(action = "task.created")
        logger.record(action = "task.deleted")
        logger.close()

        assertEquals(listOf("task.created", "task.deleted"), seen)
    }

    @Test
    fun `throwing trigger does not fail the producer or other sinks`() = runTest {
        val errors = mutableListOf<Throwable>()
        val written = mutableListOf<String>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onErrorMode(LogLevel.AUDIT, ErrorMode.PROPAGATE)
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) {
                        written.add(event.action)
                    }
                },
            )
            onEvent(onError = { _, error -> errors.add(error) }) { error("db closed") }
        }

        logger.record(action = "task.created") // must not throw
        logger.close()

        assertEquals(listOf("task.created"), written)
        assertEquals(1, errors.size)
    }

    @Test
    fun `trigger failures are never retried by the engine`() = runTest {
        var calls = 0
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
            retryMaxAttempts = 3
            retryBaseBackoffMillis = 1
            onEvents {
                calls += 1
                throw IllegalStateException("boom")
            }
        }

        logger.record(action = "task.created")
        logger.close()

        assertEquals(1, calls)
    }

    @Test
    fun `trigger honors category routing`() = runTest {
        val seen = mutableListOf<String>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onEvent(category = "db") { seen.add(it.action) }
        }

        logger.record(action = "http.request", category = "http")
        logger.record(action = "task.created", category = "db")
        logger.close()

        assertEquals(listOf("task.created"), seen)
    }

    @Test
    fun `throwing error handler cannot fail the producer`() = runTest {
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onEvent(onError = { _, _ -> error("handler failed") }) { error("db closed") }
        }

        logger.record(action = "task.created") // must not throw
        logger.close()
    }

    @Test
    fun `empty batch never invokes the trigger`() = runTest {
        var calls = 0
        val sink = TriggerSink(onEvents = { calls += 1 })

        sink.writeAll(emptyList())

        assertEquals(0, calls)
    }
}
