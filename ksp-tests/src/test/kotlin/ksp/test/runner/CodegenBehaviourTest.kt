package ksp.test.runner

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel as CoreLogLevel
import io.ilogger.core.Actor
import io.ilogger.core.ILogger
import io.ilogger.core.LogFailure
import io.ilogger.core.LogMasking
import io.ilogger.core.Outcome
import io.ilogger.core.withActor
import io.ilogger.core.withContextAttributes
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import ksp.test.generated.GeneratedRepository
import ksp.test.generated.logged
import ksp.test.library.Archive_ILogger
import ksp.test.library.AuditedCounter_ILogger
import ksp.test.library.AuditedTaskBase_ILogger
import ksp.test.library.ConfiguredRepo
import ksp.test.library.FlowingRepo
import ksp.test.library.GenericStore
import ksp.test.library.MaskingConfigRepo
import ksp.test.library.PrivateTask
import ksp.test.library.Rename_ILogger
import ksp.test.library.ResultReturningRepo
import ksp.test.library.Task
import ksp.test.library.TaskActions
import ksp.test.library.TaskRepository
import ksp.test.library.TaskScore
import ksp.test.library.TaskStatus
import ksp.test.library.ValueModelRepo
import ksp.test.library.ValueTask
import ksp.test.library.logged
import ksp.test.library.tagCount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
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
    fun `generated wrapper emits a canonical snapshot through the TestKit DSL`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            InMemoryTaskRepository().logged(logger).insert(task())

            val event = kit.assertLogged(action = "task.created").single()
            assertEquals("42", event.entityId)
            assertEquals(
                """
                schema=task.created level=AUDIT outcome=SUCCESS id=e1 ts=1700000000000 entity=Task entityId=42 source=TaskRepository.insert
                metadata:
                  priority = 5
                  title = write tests
                """.trimIndent(),
                kit.golden(event),
            )
            kit.assertNoSensitive()
        }
    }

    @Test
    fun `reserved parameter names resolve by index and compile`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            val repo = InMemoryReservedNamesRepository().logged(logger)

            assertEquals(7L, repo.save(task(7), task(9, title = "other")))
            val saved = kit.assertLogged(action = TaskActions.RESERVED_SAVED).single()
            assertEquals("other", saved.metadata["reservedTitle"])
            assertEquals("9", saved.entityId)
            assertEquals("Task", saved.entity)

            assertTrue(repo.login(task(1), "u-1"))
            val login = kit.assertLogged(action = TaskActions.RESERVED_LOGIN).single()
            assertEquals("1", login.entityId)

            val failure = runCatching { repo.fail("a", "b", null, task(3)) }.exceptionOrNull()
            assertEquals("a/b/null/3", failure?.message)
            val failed = kit.assertLogged(action = TaskActions.RESERVED_FAILED).single()
            assertEquals(Outcome.FAILURE, failed.outcome)

            kit.assertNoSensitive()
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

    @Test
    fun `cancellation is skipped by default and preserves the original exception`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val original = CancellationException("stop-now")
            val repo = object : TaskRepository by InMemoryTaskRepository() {
                override suspend fun cancelDefault(taskId: Long) {
                    throw original
                }
            }.logged(kit.logger)

            var thrown: Throwable? = null
            try {
                repo.cancelDefault(7L)
            } catch (error: Throwable) {
                thrown = error
            }

            assertSame(original, thrown)
            assertTrue(kit.sink.events().isEmpty())
        }
    }

    @Test
    fun `opt-in cancellation emits one failure marked cancelled`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val original = CancellationException("stop-logged")
            val repo = object : TaskRepository by InMemoryTaskRepository() {
                override suspend fun cancelLogged(taskId: Long) {
                    throw original
                }
            }.logged(kit.logger)

            var thrown: Throwable? = null
            try {
                repo.cancelLogged(7L)
            } catch (error: Throwable) {
                thrown = error
            }

            assertSame(original, thrown)
            assertTrue(kit.sink.awaitCount(1))
            val event = kit.sink.last()!!
            assertEquals("cancel.logged", event.action)
            assertEquals(Outcome.FAILURE, event.outcome)
            assertTrue(event.failure!!.cancelled)
            assertTrue(event.failure!!.isCancellation)
        }
    }

    @Test
    fun `interfaces generated by another processor are instrumented`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repository: GeneratedRepository = object : GeneratedRepository {
                override suspend fun run(value: String): String = "ok:$value"
            }

            val result = repository.logged(kit.logger).run("x")

            assertEquals("ok:x", result)
            assertTrue(kit.sink.awaitCount(1))
            val event = kit.sink.last()!!
            assertEquals("generated.operation", event.action)
            assertEquals(Outcome.SUCCESS, event.outcome)
            assertEquals("x", event.entityId)
        }
    }

    @Test
    fun `logging failure never replaces the original cancellation`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val delegate = kit.logger
            val throwingLogger = Proxy.newProxyInstance(
                ILogger::class.java.classLoader,
                arrayOf(ILogger::class.java),
            ) { _, method, args ->
                if (method.name == "recordNow") throw IllegalStateException("logging exploded")
                method.invoke(delegate, *(args ?: emptyArray()))
            } as ILogger

            val original = CancellationException("stop-with-broken-logger")
            val repo = object : TaskRepository by InMemoryTaskRepository() {
                override suspend fun cancelLogged(taskId: Long) {
                    throw original
                }
            }.logged(throwingLogger)

            var thrown: Throwable? = null
            try {
                repo.cancelLogged(7L)
            } catch (error: Throwable) {
                thrown = error
            }

            assertSame(original, thrown)
            assertTrue(thrown!!.suppressed.any { it.message == "logging exploded" })
        }
    }

    @Test
    fun `open class wrappers override open members and delegate via super`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val counter = AuditedCounter_ILogger(kit.logger, 10)

            assertEquals(13, counter.bump(3))

            assertTrue(kit.sink.awaitCount(1))
            val event = kit.sink.last()!!
            assertEquals(TaskActions.COUNTED_UP, event.action)
            assertEquals("3", event.entityId)
            assertNull(event.entity)
            assertEquals("AuditedCounter.bump", event.source)
        }
    }

    @Test
    fun `abstract class wrappers instrument open members through super`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val base = AuditedTaskBaseChild(kit.logger)

            assertEquals(42L, base.store(task()))
            val stored = kit.assertLogged(action = TaskActions.BASE_STORED).single()
            assertEquals("42", stored.entityId)
            assertEquals("write tests", stored.metadata["title"])
            assertEquals("AuditedTaskBase.store", stored.source)

            assertEquals("42:OPEN", base.label(task()))
            assertTrue(kit.sink.awaitCount(2))
            val labeled = kit.sink.last()!!
            assertEquals(TaskActions.BASE_LABELED, labeled.action)
            assertEquals("42", labeled.entityId)
            assertEquals("AuditedTaskBase.label", labeled.source)
        }
    }

    @Test
    fun `top level functions get adapter wrappers`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            assertEquals(42L, Archive_ILogger.archive(kit.logger, task()))
            val archived = kit.assertLogged(action = TaskActions.ARCHIVED).single()
            assertEquals("42", archived.entityId)
            assertEquals("Task", archived.entity)
            assertEquals("archive", archived.source)

            assertEquals("renamed", Rename_ILogger.rename(kit.logger, task(), "renamed"))
            assertTrue(kit.sink.awaitCount(2))
            val renamed = kit.sink.last()!!
            assertEquals(TaskActions.RENAMED, renamed.action)
            assertEquals("42", renamed.entityId)
            assertEquals("Task", renamed.entity)
        }
    }

    @Test
    fun `top level extension functions get logger-first extensions`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            assertEquals(0, task().tagCount(kit.logger))

            val counted = kit.assertLogged(action = TaskActions.TAG_COUNTED).single()
            assertEquals("0", counted.entityId)
            assertEquals("Task", counted.entity)
            assertEquals("tagCount", counted.source)
        }
    }

    @Test
    fun `generic interface wrappers keep type parameters and emit parameter-derived events`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val store = InMemoryGenericStore().logged(kit.logger)

            assertEquals(7L, store.store(7, "item"))
            val stored = kit.assertLogged(action = TaskActions.GENERIC_STORED).single()
            assertNull(stored.entity) // a type parameter is not a provable entity
            assertEquals("7", stored.entityId)
            assertEquals("stored", stored.metadata["kind"])

            assertEquals("TASK-9", store.transform(9) { it.uppercase() })
            val transformed = kit.assertLogged(action = TaskActions.GENERIC_TRANSFORMED).single()
            assertNull(transformed.entity)
            assertEquals("9", transformed.entityId)
        }
    }

    @Test
    fun `result and nullable returns unwrap for entity id selection`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryResultRepo().logged(kit.logger)

            repo.saveWrapped(task(42))
            val wrapped = kit.assertLogged(action = TaskActions.WRAPPED_SAVED).single()
            assertEquals("42", wrapped.entityId)
            assertEquals("Task", wrapped.entity)

            repo.saveNullable(task(42))
            val nullable = kit.assertLogged(action = TaskActions.NULLABLE_SAVED).single()
            assertEquals("42", nullable.entityId)
            assertEquals("Task", nullable.entity)

            repo.register("label")
            val registered = kit.assertLogged(action = TaskActions.REGISTERED).single()
            assertEquals("99", registered.entityId)
            assertNull(registered.entity) // Long is not a domain candidate
        }
    }

    @Test
    fun `flow functions record a success event on normal completion`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryFlowingRepo().logged(kit.logger)

            val collected = mutableListOf<Task>()
            withContextAttributes("requestId" to "r-9") {
                repo.observe(7).collect { collected += it }
            }

            assertEquals(listOf(7L), collected.map { it.id })
            val event = kit.assertLogged(action = TaskActions.OBSERVED).single()
            assertEquals(Outcome.SUCCESS, event.outcome)
            assertEquals("7", event.entityId)
            assertEquals("Task", event.entity)
            assertEquals("r-9", event.metadata["requestId"])
            assertEquals("FlowingRepo.observe", event.source)
        }
    }

    @Test
    fun `flow start precedes success and failure preserves the stream error`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryFlowingRepo().logged(kit.logger)
            repo.watchAll().collect { }

            val events = kit.sink.events()
            assertEquals(2, events.size)
            assertEquals(Outcome.START, events[0].outcome)
            assertEquals(Outcome.SUCCESS, events[1].outcome)
            assertEquals("all", events[1].entityId)

            val boom = RuntimeException("stream blew")
            val failing = object : FlowingRepo {
                override fun observe(taskId: Long): Flow<Task> = flowOf(task(taskId))
                override fun watchAll(): Flow<Task> = flow { emit(task(1)); throw boom }
            }.logged(kit.logger)

            val thrown = runCatching { failing.watchAll().collect { } }.exceptionOrNull()
            assertSame(boom, thrown)
            assertTrue(thrown!!.suppressed.isEmpty())

            val failed = kit.sink.events().drop(2)
            assertEquals(2, failed.size)
            assertEquals(Outcome.START, failed[0].outcome)
            assertEquals(Outcome.FAILURE, failed[1].outcome)
            assertEquals("stream blew", failed[1].failure?.message)
        }
    }

    @Test
    fun `cancelled flow collection records start only by default`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = InMemoryFlowingRepo().logged(kit.logger)

            val started = CompletableDeferred<Unit>()
            val job = launch {
                repo.watchAll().collect {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            job.cancelAndJoin()

            val events = kit.sink.events()
            assertEquals(1, events.size)
            assertEquals(Outcome.START, events.single().outcome)
        }
    }

    @Test
    fun `expanded value model renders scalars containers and adapters`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            object : ValueModelRepo {}.logged(kit.logger).score(valueTask(), TaskScore(9))

            val event = kit.assertLogged(action = TaskActions.SCORED).single()
            assertEquals("ValueTask", event.entity)
            assertEquals("77", event.entityId)
            assertEquals(9, event.metadata["score"])            // ScoreAdapter renders the points
            assertEquals("77", event.metadata["taskRef"])       // whole-object adapter
            assertEquals("alpha|beta", event.metadata["tags"])  // TagsAdapter reshapes the list
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), event.metadata["createdAt"])
            assertEquals(Duration.ofHours(2), event.metadata["deadline"])
            assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), event.metadata["reference"])
            assertEquals(BigDecimal("19.99"), event.metadata["cost"])
            assertTrue((event.metadata["flags"] as IntArray).contentEquals(intArrayOf(1, 2, 3)))
            assertEquals(mapOf("env" to "test"), event.metadata["attributes"])
            assertEquals('x', event.metadata["label"])
            assertEquals(3u, event.metadata["attempts"])
        }
    }

    @Test
    fun `class config supplies defaults explicit values win and audit stays pinned`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            val repo = object : ConfiguredRepo {}.logged(logger)

            repo.store(task())
            val stored = kit.assertLogged(action = TaskActions.CONFIG_STORED).single()
            assertEquals(CoreLogLevel.INFO, stored.level)   // inherited from @LogConfig
            assertEquals("config", stored.category)         // inherited from @LogConfig

            repo.warn(task())
            val warned = kit.assertLogged(action = TaskActions.CONFIG_WARNED).single()
            assertEquals(CoreLogLevel.WARN, warned.level)   // explicit override wins
            assertEquals("config", warned.category)

            repo.audit(task())
            val audited = kit.assertLogged(action = TaskActions.CONFIG_AUDITED).single()
            assertEquals(CoreLogLevel.AUDIT, audited.level) // useClassDefaults = false
            assertNull(audited.category)                    // ...and skips the class category too
        }
    }

    @Test
    fun `noLog member emits nothing`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            val repo = object : ConfiguredRepo {}.logged(logger)

            assertEquals(42L, repo.silent(task()))

            assertTrue(kit.sink.events().none { it.action == "config.never" })
            assertTrue(kit.sink.events().isEmpty())
        }
    }

    @Test
    fun `class sensitive policy masks policy-less selections`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            object : MaskingConfigRepo {}.logged(logger).reveal(PrivateTask(7L, "secret-ish"))

            val event = kit.assertLogged(action = TaskActions.CONFIG_MASKED).single()
            assertEquals("PrivateTask", event.entity)
            assertEquals("fixed", event.entityId)
            assertEquals(LogMasking.MASK, event.metadata["ownerId"]) // sensitive id masked by class policy
            assertEquals("secret-ish", event.metadata["title"])      // non-sensitive selection untouched
        }
    }
}

private fun valueTask() = ValueTask(
    id = 77,
    label = 'x',
    attempts = 3u,
    reference = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    deadline = Duration.ofHours(2),
    cost = BigDecimal("19.99"),
    flags = intArrayOf(1, 2, 3),
    attributes = mapOf("env" to "test"),
    tags = listOf("alpha", "beta"),
)

private class InMemoryGenericStore : GenericStore<String> {
    override suspend fun store(id: Long, item: String): Long = id
    override fun <R : Any> transform(id: Long, mapper: (String) -> R): R = mapper("task-$id")
}

private class InMemoryResultRepo : ResultReturningRepo {
    override suspend fun saveWrapped(task: Task): Result<Task> = Result.success(task)
    override suspend fun saveNullable(task: Task): Task? = task
    override suspend fun register(label: String): Result<Long> = Result.success(99)
}

private class InMemoryFlowingRepo : FlowingRepo {
    override fun observe(taskId: Long): Flow<Task> =
        flowOf(Task(taskId, "write tests", 5, TaskStatus.OPEN, emptyList()))

    override fun watchAll(): Flow<Task> =
        flowOf(Task(1, "first", 1, TaskStatus.OPEN, emptyList()), Task(2, "second", 2, TaskStatus.DONE, emptyList()))
}

private class AuditedTaskBaseChild(logger: ILogger) : AuditedTaskBase_ILogger(logger)
