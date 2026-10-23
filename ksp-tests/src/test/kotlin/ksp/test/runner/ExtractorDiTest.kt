package ksp.test.runner

import io.ilogger.testing.TestKit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import ksp.test.library.ExtractorRepo
import ksp.test.library.InjectedSummaryExtractor
import ksp.test.library.Task
import ksp.test.library.TaskActions
import ksp.test.library.TaskStatus
import ksp.test.library.logged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-24: generated wrappers resolve extractors through the logger's
 * `ExtractorRegistry`, reuse one instance, and validate every output.
 */
class ExtractorDiTest {

    private val task = Task(
        id = 7L,
        title = "repair coffee machine",
        priority = 2,
        status = TaskStatus.OPEN,
        tags = listOf("home"),
    )

    private fun repo() = object : ExtractorRepo {
        override suspend fun injected(task: Task): Int = task.priority
        override suspend fun asObject(task: Task): Int = task.priority
        override suspend fun unsafe(task: Task): Int = task.priority
        override suspend fun throwing(task: Task): Int = task.priority
    }

    @Test
    fun `registered factory is reused across wrappers and events`() = runTest {
        val calls = AtomicInteger()
        val kit = TestKit.create {
            extractor(InjectedSummaryExtractor::class) {
                calls.incrementAndGet()
                InjectedSummaryExtractor("di")
            }
        }
        kit.logger.use { logger ->
            val repo = repo()
            repo.logged(logger).injected(task)
            repo.logged(logger).injected(task)

            val events = kit.assertLogged(action = TaskActions.INJECTED_SUMMARY)
            assertEquals(2, events.size)
            assertEquals("di:repair coffee machine", events.first().metadata["summary"])
            assertEquals("one instance per logger", 1, calls.get())
            assertTrue(logger.extractors === logger.extractors)
        }
    }

    @Test
    fun `unregistered injected extractor fails at first use`() {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            val error = assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    repo().logged(logger).injected(task)
                }
            }
            assertTrue(
                "message names the extractor and the fix",
                error.message.orEmpty().contains("InjectedSummaryExtractor") &&
                    error.message.orEmpty().contains("extractor("),
            )
        }
    }

    @Test
    fun `object extractor falls back to the object instance`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            repo().logged(logger).asObject(task)

            val event = kit.assertLogged(action = TaskActions.OBJECT_SUMMARY).single()
            assertEquals("object:7", event.metadata["summary"])
        }
    }

    @Test
    fun `unsafe extractor output is omitted`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            repo().logged(logger).unsafe(task)

            val event = kit.assertLogged(action = TaskActions.UNSAFE_SUMMARY).single()
            assertNull("a whole object must never reach an event", event.metadata["summary"])
            assertFalse(
                "the raw task must not be stored under another projection",
                event.metadata.values.any { it is Task },
            )
        }
    }

    @Test
    fun `throwing extractor omits its entry and keeps the event`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            repo().logged(logger).throwing(task)

            val event = kit.assertLogged(action = TaskActions.THROWING_SUMMARY).single()
            assertNull(event.metadata["summary"])
            assertEquals("repair coffee machine", event.metadata["title"])
        }
    }
}
