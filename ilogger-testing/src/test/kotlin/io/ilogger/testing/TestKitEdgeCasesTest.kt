package io.ilogger.testing

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.FieldChange
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.LogMasking
import io.ilogger.core.Outcome
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestKitEdgeCasesTest {

    private fun event(
        id: String = "edge-1",
        metadata: Map<String, Any?> = emptyMap(),
        outcome: Outcome = Outcome.SUCCESS,
        failure: LogFailure? = null,
    ): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.AUDIT,
        action = "edge.event",
        metadata = metadata,
        outcome = outcome,
        failure = failure,
    )

    @Test
    fun `awaitEvent reports captured actions on timeout`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            logger.info("present")

            val failure = runCatching {
                kit.awaitEvent(timeoutMillis = 10) { it.action == "missing" }
            }.exceptionOrNull()

            assertTrue(failure is AssertionError)
            assertTrue(failure!!.message!!.contains("No event matched within 10ms"))
            assertTrue(failure.message!!.contains("Captured actions: log.info"))
        }
    }

    @Test
    fun `assertLogged explains at-least-one failures`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val failure = runCatching { kit.assertLogged(action = "missing") }.exceptionOrNull()

            assertTrue(failure is AssertionError)
            assertTrue(failure!!.message!!.contains("Expected at least one event(s)"))
            assertTrue(failure.message!!.contains("level=*"))
            assertTrue(failure.message!!.contains("but found 0"))
        }
    }

    @Test
    fun `drain times out while a bound sink stays blocked`() = runBlocking {
        val blocked = BlockingLogSink()
        val kit = TestKit.create {
            dispatch = DispatchMode.ASYNC
            batchSize = 1
            sink(blocked)
        }
        try {
            kit.logger.recordNow(action = "audit.blocked", dispatch = DispatchMode.ASYNC)
            assertTrue(blocked.awaitFirstWrite(2_000))

            assertFalse(kit.drain(timeoutMillis = 20))
        } finally {
            kit.logger.close()
        }
    }

    @Test
    fun `golden renders every populated field`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            kit.sink.write(
                LogEvent(
                    id = "g1",
                    timestampMillis = 1_700_000_000_000,
                    level = LogLevel.ERROR,
                    action = "task.update",
                    actionVersion = 2,
                    actor = Actor.user("u1", name = "Ada"),
                    entity = "Task",
                    entityId = "42",
                    metadata = linkedMapOf("b" to 2, "a" to 1),
                    changes = listOf(FieldChange("title", "old", "new")),
                    source = "TaskService",
                    category = "audit",
                    correlationId = "corr-1",
                    causationId = "cause-1",
                    outcome = Outcome.FAILURE,
                    failure = LogFailure(
                        errorType = "java.lang.IllegalStateException",
                        message = "boom",
                        rootCauseType = "java.io.IOException",
                        stackSummary = listOf("A", "B"),
                        cancelled = true,
                    ),
                ),
            )

            val expected = """
                schema=task.update@v2 level=ERROR outcome=FAILURE id=g1 ts=1700000000000 actor=USER:u1/Ada entity=Task entityId=42 source=TaskService category=audit corr=corr-1 cause=cause-1
                metadata:
                  a = 1
                  b = 2
                changes:
                  title: old → new
                failure: java.lang.IllegalStateException root=java.io.IOException message=boom cancelled
                  at A
                  at B
            """.trimIndent()

            assertEquals(expected, kit.golden(kit.sink.last()!!))
        }
    }

    @Test
    fun `golden omits absent failure details`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            kit.sink.write(
                event(
                    outcome = Outcome.FAILURE,
                    failure = LogFailure(
                        errorType = "java.lang.RuntimeException",
                        message = null,
                        rootCauseType = null,
                        stackSummary = emptyList(),
                    ),
                ),
            )

            assertTrue(kit.golden(kit.sink.last()!!).endsWith("failure: java.lang.RuntimeException"))
        }
    }

    @Test
    fun `assertNoSensitive accepts nulls and inspects iterables`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            kit.sink.write(event(metadata = mapOf("password" to null)))
            kit.assertNoSensitive()

            kit.sink.clear()
            kit.sink.write(
                event(metadata = mapOf("contacts" to listOf(mapOf("email" to "user@example.com")))),
            )

            val failure = runCatching { kit.assertNoSensitive() }.exceptionOrNull()

            assertTrue(failure is AssertionError)
            assertTrue(failure!!.message!!.contains("contacts.email = user@example.com"))
        }
    }

    @Test
    fun `assertNoSensitive only treats lowercase sha-256 shaped values as hashed`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val longNonHex = "z".repeat(LogMasking.HASH_LENGTH)
            kit.sink.write(event(metadata = mapOf("token" to longNonHex)))
            val nonHex = runCatching { kit.assertNoSensitive() }.exceptionOrNull()
            assertTrue(nonHex is AssertionError)
            assertTrue(nonHex!!.message!!.contains(longNonHex))

            kit.sink.clear()
            val uppercaseHex = "A".repeat(LogMasking.HASH_LENGTH)
            kit.sink.write(event(metadata = mapOf("token" to uppercaseHex)))
            val uppercase = runCatching { kit.assertNoSensitive() }.exceptionOrNull()
            assertTrue(uppercase is AssertionError)
            assertTrue(uppercase!!.message!!.contains(uppercaseHex))

            kit.sink.clear()
            val shortHex = "a".repeat(LogMasking.HASH_LENGTH - 1)
            kit.sink.write(event(metadata = mapOf("token" to shortHex)))
            val short = runCatching { kit.assertNoSensitive() }.exceptionOrNull()
            assertTrue(short is AssertionError)
        }
    }

    @Test
    fun `assertNoSensitive ignores blank custom keys`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            kit.sink.write(event(metadata = mapOf("count" to 3)))
            kit.assertNoSensitive("", "  ")
        }
    }
}
