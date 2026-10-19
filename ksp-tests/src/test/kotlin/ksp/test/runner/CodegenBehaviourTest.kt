package ksp.test.runner

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel as CoreLogLevel
import io.ilogger.core.Actor
import io.ilogger.core.LogFailure
import io.ilogger.core.Outcome
import io.ilogger.core.withActor
import io.ilogger.core.withContextAttributes
import java.util.concurrent.atomic.AtomicReference
import ksp.test.library.Task
import ksp.test.library.TaskRepository
import ksp.test.library.TaskStatus
import ksp.test.library.logged
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import io.ilogger.testing.TestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end verification of `:ilogger-ksp`: annotation processing runs at
 * build time; these tests exercise the generated wrappers.
 */
class CodegenBehaviourTest {

    private fun task(id: Long = 42, title: String = "write tests", status: TaskStatus = TaskStatus.OPEN) =
        Task(id, title, 5, status, emptyList())

    @Test
    fun `insert emits audit event with entity, entity id and metadata`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            val id = repo.insert(task())

            assertEquals(42L, id)
            val event = kit.sink.last()!!
            assertEquals("task.created", event.action)
            assertEquals("task.created", event.schemaId) // actionVersion defaults to v1
            assertEquals(CoreLogLevel.AUDIT, event.level)
            assertEquals(Outcome.SUCCESS, event.outcome)
            assertEquals("Task", event.entity)
            assertEquals("42", event.entityId)
            assertEquals("TaskRepository.insert", event.source)
            assertNull(event.failure)
            assertNull(event.category)
            assertEquals("write tests", event.metadata["title"])
            assertEquals(5, event.metadata["priority"])
            assertTrue(event.changes.isEmpty())
        }
    }

    @Test
    fun `disabled logger skips instrumentation with zero events`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            kit.logger.enabled = false
            val repo = InMemoryTaskRepository().logged(kit.logger)
            val id = repo.insert(task())
            assertEquals(42L, id)
            assertTrue(kit.sink.events().isEmpty())
        }
    }

    @Test
    fun `start event precedes success for before`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            repo.delete(task())

            val events = kit.sink.events()
            assertEquals(2, events.size)
            assertEquals(Outcome.START, events[0].outcome)
            assertEquals(Outcome.SUCCESS, events[1].outcome)
            assertEquals("task.deleted", events[0].action)
            assertEquals("42", events[0].entityId) // param-derived id is available before the call
            assertTrue(events[0].changes.isEmpty())
        }
    }

    @Test
    fun `failure emits failure event and never swallows the original error`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            val original = AtomicReference<Throwable?>(null)

            runCatching { repo.purge(task()) }
                .onFailure { original.set(it) }

            assertTrue(original.get() is IllegalStateException)
            assertSame("db offline", original.get()!!.message)

            val event = kit.sink.last()!!
            assertEquals(Outcome.FAILURE, event.outcome)
            assertEquals("task.purged", event.action)
            assertEquals("42", event.entityId) // param-derived id survives failures
            val failure: LogFailure = event.failure!!
            assertTrue(failure.errorType.contains("IllegalStateException"))
            assertEquals("db offline", failure.message)
        }
    }

    @Test
    fun `changes record before and after values on success`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            repo.update(task(title = "old title"), newTitle = "new title")

            val event = kit.sink.last()!!
            assertEquals("task.updated", event.action)
            assertEquals("OPEN", event.metadata["status"]) // enum rendered as name
            val change = event.changes.single()
            assertEquals("title", change.field)
            assertEquals("old title", change.before)
            assertEquals("new title", change.after)
        }
    }

    @Test
    fun `constant and context metadata plus category and level`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            withContextAttributes("requestId" to "r-77") {
                repo.close(9)
            }

            val event = kit.sink.last()!!
            assertEquals("task.closed", event.action)
            assertEquals(CoreLogLevel.INFO, event.level)
            assertEquals("workflows", event.category)
            assertEquals("closure", event.metadata["event"])
            assertEquals("r-77", event.metadata["requestId"])
            assertNull(event.entity) // no entity type is provable
            assertEquals("9", event.entityId)
        }
    }

    @Test
    fun `ambient actor is resolved from withActor`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            withActor(Actor.user("u-1")) {
                repo.insert(task())
            }
            assertEquals("u-1", kit.sink.last()!!.actor?.id)
        }
    }

    @Test
    fun `explicit actor override wins over ambient`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            val overrideRef = AtomicReference<Actor?>(null)
            withActor(Actor.user("ambient")) {
                repo.assign(task(), userId = "u-9")
                overrideRef.set(kit.sink.last()!!.actor)
            }
            assertEquals("u-9", overrideRef.get()?.id)
        }
    }

    @Test
    fun `extractor metadata converts whole object`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            repo.summarize(task())
            assertEquals("write tests", kit.sink.last()!!.metadata["summary"])
        }
    }

    @Test
    fun `batch insert has no provable single entity id`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            val ids = repo.insertAll(task(1), task(2), task(3))

            assertEquals(listOf(1L, 2L, 3L), ids)
            val event = kit.sink.last()!!
            assertEquals("task.batchCreated", event.action)
            assertNull(event.entityId)
            assertEquals("Task", event.entity) // batch ops still attribute the entity type
            assertEquals(3, event.metadata["count"])
            assertTrue(event.changes.isEmpty())
        }
    }

    @Test
    fun `non suspend functions record via recordNow`() {
        runBlocking {
            val kit = TestKit.create { dispatch = DispatchMode.ASYNC }
            kit.logger.use {
                val repo = InMemoryTaskRepository().logged(kit.logger)
                val found = repo.findById(7)
                assertEquals(7L, found.id)

                assertTrue(kit.sink.awaitCount(1))
                val event = kit.sink.last()!!
                assertEquals("task.found.byId", event.action)
                assertEquals("Task", event.entity)
                assertEquals("7", event.entityId)
                assertEquals(Outcome.SUCCESS, event.outcome)
            }
        }
    }

    @Test
    fun `inline dispatch override waits synchronously`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            repo.inlineDelete(task())
            // INLINE delivery means the event is already there.
            assertEquals(1, kit.sink.count())
            assertEquals(42L, kit.sink.last()!!.metadata["id"])
            assertEquals("emergency", kit.sink.last()!!.category)
            assertEquals("task.deleted@v2", kit.sink.last()!!.schemaId)
        }
    }

    @Test
    fun `unannotated members delegate untouched`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            assertEquals(43, repo.passthrough(42))
            assertTrue(kit.sink.events().isEmpty())
        }
    }

    @Test
    fun `result entities return values are respected for info levels too`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryTaskRepository().logged(kit.logger)
            repo.update(task(), "renamed")
            assertFalse(kit.sink.events().isEmpty())
        }
    }
}
