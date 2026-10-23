package io.ilogger.testing

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogMasking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestKitTest {

    @Test
    fun `defaults are deterministic and synchronous`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            logger.info("hello", "attempt" to 1)

            val event = kit.sink.last()!!
            assertEquals("e1", event.id)
            assertEquals(1_700_000_000_000, event.timestampMillis)
            assertEquals(LogLevel.INFO, event.level)
            assertEquals("log.info", event.action)
            assertEquals("hello", event.metadata["message"])
            assertEquals(1, event.metadata["attempt"])

            kit.clock.set(1_700_000_123_000)
            logger.warn("later")

            assertEquals("e2", kit.sink.last()!!.id)
            assertEquals(1_700_000_123_000, kit.sink.last()!!.timestampMillis)
        }
    }

    @Test
    fun `configure applies builder overrides`() = runTest {
        val kit = TestKit.create {
            minLevel = LogLevel.WARN
        }
        kit.logger.use { logger ->
            logger.info("filtered")
            logger.warn("kept")

            assertEquals(1, kit.sink.count())
            assertEquals("log.warn", kit.sink.last()!!.action)
        }
    }

    @Test
    fun `sink snapshots are immutable and clear keeps it usable`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            logger.info("first")
            val snapshot = kit.sink.events()

            logger.info("second")

            assertEquals(1, snapshot.size)
            assertEquals(2, kit.sink.events().size)
            assertEquals("log.info", kit.sink.last()!!.action)

            kit.sink.clear()
            assertEquals(0, kit.sink.count())
            assertNull(kit.sink.last())

            logger.info("third")
            assertEquals(1, kit.sink.count())
        }
    }

    @Test
    fun `awaitCount drains asynchronous dispatch`() = runTest {
        val kit = TestKit.create {
            dispatch = DispatchMode.BUFFERED
            flushIntervalMillis = 250
            batchSize = 16
        }
        kit.logger.use { logger ->
            logger.record(action = "audit.event", metadata = mapOf("k" to "v"))

            assertTrue(kit.sink.awaitCount(1))
            assertEquals("audit.event", kit.sink.last()!!.action)
        }
    }

    @Test
    fun `disabled logger records nothing`() = runTest {
        val kit = TestKit.create { enabled = false }
        kit.logger.use { logger ->
            logger.info("ignored")
            logger.record(action = "audit.ignored")

            assertEquals(0, kit.sink.count())
            assertFalse(logger.enabled)
        }
    }

    @Test
    fun `sequential id generator never repeats`() {
        val generator = SequentialIdGenerator()
        val ids = List(100) { generator.next() }
        assertEquals(100, ids.toSet().size)
        assertEquals("e1", ids.first())
        assertEquals("e100", ids.last())
    }

    @Test
    fun `assertion helpers filter by action level and count`() {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            logger.info("one")
            logger.warn("two")
            logger.info("three")

            assertEquals(2, kit.eventsFor("log.info").size)
            assertEquals(1, kit.assertLogged(action = "log.warn", level = LogLevel.WARN).size)
            assertEquals(2, kit.assertLogged(action = "log.info", times = 2).size)
            assertEquals(2, kit.assertLogged(level = LogLevel.INFO).size)

            val tooFew = runCatching { kit.assertLogged(action = "log.info", times = 1) }
                .exceptionOrNull()
            assertTrue(tooFew is AssertionError)
            assertTrue(tooFew!!.message!!.contains("but found 2"))

            kit.assertNotLogged(action = "log.error")
            val unexpected = runCatching { kit.assertNotLogged(level = LogLevel.INFO) }
                .exceptionOrNull()
            assertTrue(unexpected is AssertionError)
        }
    }

    @Test
    fun `awaitEvent suspends for asynchronous delivery`() = runTest {
        val kit = TestKit.create {
            dispatch = DispatchMode.ASYNC
            batchSize = 1
        }
        kit.logger.use { logger ->
            logger.recordNow(action = "audit.late", metadata = mapOf("k" to "v"))

            val event = kit.awaitEvent { it.action == "audit.late" }
            assertEquals("v", event.metadata["k"])
        }
    }

    @Test
    fun `drain waits for async delivery without sleeps`() = runTest {
        val kit = TestKit.create {
            dispatch = DispatchMode.ASYNC
            batchSize = 4
        }
        kit.logger.use { logger ->
            repeat(10) { logger.recordNow(action = "audit.drain") }

            assertTrue(kit.drain())
            assertEquals(10, kit.sink.count())
            assertTrue(logger.awaitIdle(1_000))
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `test worker dispatcher drives async pipelines deterministically`() = runTest {
        val kit = TestKit.create(UnconfinedTestDispatcher(testScheduler)) {
            dispatch = DispatchMode.ASYNC
            batchSize = 1
        }
        kit.logger.use { logger ->
            logger.recordNow(action = "audit.dispatch")

            assertEquals("audit.dispatch", kit.assertLogged(action = "audit.dispatch").single().action)
            assertTrue(logger.awaitIdle(1_000))
        }
    }

    @Test
    fun `assertNoSensitive rejects plain values but accepts masked and hashed ones`() {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            logger.recordBlocking(
                action = "user.login",
                metadata = mapOf("password" to "hunter2"),
                dispatch = DispatchMode.INLINE,
            )

            val leaked = runCatching { kit.assertNoSensitive() }.exceptionOrNull()
            assertTrue(leaked is AssertionError)
            assertTrue(leaked!!.message!!.contains("password = hunter2"))

            kit.sink.clear()
            logger.recordBlocking(
                action = "user.login",
                metadata = mapOf("profile" to mapOf("email" to "user@example.com")),
                dispatch = DispatchMode.INLINE,
            )
            val nested = runCatching { kit.assertNoSensitive() }.exceptionOrNull()
            assertTrue(nested is AssertionError)
            assertTrue(nested!!.message!!.contains("profile.email = user@example.com"))

            kit.sink.clear()
            logger.recordBlocking(
                action = "user.login",
                metadata = mapOf(
                    "password" to LogMasking.MASK,
                    "access_token" to LogMasking.hash("secret"),
                ),
                dispatch = DispatchMode.INLINE,
            )
            kit.assertNoSensitive()
            kit.assertNoSensitive("access_token")

            kit.sink.clear()
            logger.recordBlocking(
                action = "user.login",
                metadata = mapOf("internalPin" to "1234"),
                dispatch = DispatchMode.INLINE,
            )
            kit.assertNoSensitive()
            val custom = runCatching { kit.assertNoSensitive("pin") }.exceptionOrNull()
            assertTrue(custom is AssertionError)
            assertTrue(custom!!.message!!.contains("internalPin = 1234"))
        }
    }

    @Test
    fun `golden snapshot is canonical and deterministic`() {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            logger.info("hello", "attempt" to 1)

            val event = kit.assertLogged(action = "log.info").single()
            val expected = """
                schema=log.info level=INFO outcome=SUCCESS id=e1 ts=1700000000000 source=ILogger
                metadata:
                  attempt = 1
                  message = hello
            """.trimIndent()
            assertEquals(expected, kit.golden(event))

            kit.advanceTimeBy(1_000)
            logger.info("later")
            assertEquals(1_700_000_001_000, kit.sink.last()!!.timestampMillis)
        }
    }
}
