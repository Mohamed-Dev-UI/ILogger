package com.mohamed.dev.ui.ilogger.logging

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogChange
import io.ilogger.annotations.LogLevel
import io.ilogger.annotations.LogMeta
import io.ilogger.annotations.SensitiveHandling
import io.ilogger.core.ILogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The application's accountable operations. Every method is a compile-time
 * audit contract: the processor proves entity/id sources, validates value
 * expressions and generates the `.logged(logger)` wrapper.
 *
 * The storage calls behind these methods are instrumented too
 * ([TaskDao]); [DemoSession.storage] suppresses those duplicate events by
 * default (the documented `suppressSources` pattern) while a raw-storage
 * action in the UI deliberately shows them.
 */
interface TaskRepository {

    @Log(
        action = TaskActions.CREATED,
        entity = Task::class,
        entityId = "result",
        metadata = [
            LogMeta("title"),
            LogMeta("priority"),
            // ambient LogContext attribute -> metadata key "tenantId"
            LogMeta("context.tenantId"),
        ],
        description = "A task was created; the id comes from the insert result.",
    )
    suspend fun create(title: String, priority: TaskPriority = TaskPriority.NORMAL): Long

    @Log(
        action = TaskActions.RENAMED,
        entity = Task::class,
        entityId = "id",
        metadata = [LogMeta("id", name = "taskId")],
        changes = [LogChange(field = "title", from = "currentTitle", to = "newTitle")],
        onFailure = true,
    )
    suspend fun rename(id: Long, currentTitle: String, newTitle: String): Boolean

    @Log(
        action = TaskActions.COMPLETED,
        entity = Task::class,
        entityId = "id",
        metadata = [LogMeta("actor.name"), LogMeta("context.tenantId")],
    )
    suspend fun complete(id: Long): Boolean

    @Log(
        action = TaskActions.DELETED,
        entity = Task::class,
        entityId = "id",
        before = true,
        onFailure = true,
    )
    suspend fun delete(id: Long): Boolean

    @Log(
        action = TaskActions.FAILED,
        entity = Task::class,
        entityId = "id",
        onFailure = true,
    )
    suspend fun explode(id: Long)

    @Log(
        action = TaskActions.ARCHIVED,
        schemaVersion = 2,
        entity = Task::class,
        category = "audit",
        metadata = [
            // The same sensitive value, explicitly selected under two policies.
            LogMeta("task.privateNote", name = "noteFingerprint", sensitive = SensitiveHandling.HASH),
            LogMeta("task.privateNote", name = "noteMasked", sensitive = SensitiveHandling.MASK),
            LogMeta("task.priority"),
            LogMeta("actor.type"),
            LogMeta("actor.name"),
        ],
        changes = [LogChange(field = "status", from = "task.status", to = "'ARCHIVED'")],
    )
    suspend fun archive(task: Task): Boolean

    @Log(
        action = TaskActions.ESCALATED,
        entity = Task::class,
        entityId = "id",
        actor = "operatorId",
        metadata = [LogMeta("operatorId", name = "operator")],
    )
    suspend fun escalate(id: Long, operatorId: String): Boolean

    @Log(
        action = TaskActions.BATCH_CREATED,
        entity = Task::class,
        metadata = [
            LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count"),
            LogMeta("'import'", name = "channel"),
        ],
    )
    suspend fun insertBatch(tasks: List<Task>): List<Long>

    @Log(
        action = TaskActions.SUMMARIZED,
        entity = Task::class,
        metadata = [LogMeta("task", extractor = TaskSummaryExtractor::class, name = "summary")],
    )
    suspend fun summarize(task: Task): Int

    @Log(
        action = TaskActions.INLINE_AUDITED,
        entity = Task::class,
        entityId = "id",
        dispatch = DispatchMode.INLINE,
        category = "audit",
    )
    suspend fun auditInline(id: Long): Boolean

    /** No annotation here on purpose: manual `record` at a boundary codegen cannot see. */
    suspend fun search(query: String): List<Task>
}

/**
 * Room-backed implementation. `dao` is the already-wrapped [TaskDao] supplied
 * by the Hilt graph; this class is wrapped once more by [TaskRepository]'s
 * generated `.logged(logger)` in [LoggingModule].
 */
@Singleton
class RoomTaskRepository @Inject constructor(
    private val dao: TaskDao,
    private val session: DemoSession,
    private val logger: ILogger,
) : TaskRepository {

    override suspend fun create(title: String, priority: TaskPriority): Long {
        val normalized = title.trim()
        val task = Task(
            title = normalized,
            priority = priority,
            privateNote = "internal note for \"$normalized\"",
        )
        return session.storage { dao.insert(task) }
    }

    override suspend fun rename(id: Long, currentTitle: String, newTitle: String): Boolean =
        session.storage { dao.rename(id, newTitle.trim()) } > 0

    override suspend fun complete(id: Long): Boolean =
        session.storage { dao.complete(id) } > 0

    override suspend fun delete(id: Long): Boolean =
        session.storage { dao.delete(id) } > 0

    override suspend fun explode(id: Long) {
        throw IllegalStateException("demo failure for task $id — the audit wrapper rethrows this verbatim")
    }

    override suspend fun archive(task: Task): Boolean =
        session.storage { dao.archive(task.id) } > 0

    override suspend fun escalate(id: Long, operatorId: String): Boolean =
        session.storage { dao.assign(id, operatorId.trim()) } > 0

    override suspend fun insertBatch(tasks: List<Task>): List<Long> =
        session.storage { dao.insertAll(tasks) }

    override suspend fun summarize(task: Task): Int = task.title.length

    override suspend fun auditInline(id: Long): Boolean =
        session.storage { dao.byId(id) != null }

    override suspend fun search(query: String): List<Task> {
        val normalized = query.trim()
        val rows = dao.search(normalized)
        logger.record(
            action = TaskActions.SEARCHED,
            level = LogLevel.INFO,
            entity = "TaskQuery",
            entityId = normalized,
            metadata = mapOf("query" to normalized, "hits" to rows.size),
            source = "TaskRepository.search",
            category = "diagnostics",
        )
        return rows
    }
}

/**
 * A synchronous cache must use the `recordNow` path. Generated wrappers for
 * non-suspend methods read the thread-local bridge, which the controller
 * establishes with `LogContexts.onCurrentThread`.
 */
interface SessionCache {

    @Log(
        action = TaskActions.CACHE_WRITTEN,
        entity = Task::class,
        entityId = "task.id",
        metadata = [LogMeta("task.title"), LogMeta("task.priority")],
        dispatch = DispatchMode.ASYNC,
    )
    fun put(task: Task)

    @Log(
        action = TaskActions.CACHE_EVICTED,
        entity = Task::class,
        entityId = "id",
        dispatch = DispatchMode.ASYNC,
    )
    fun evict(id: Long)
}

@Singleton
class InMemorySessionCache @Inject constructor() : SessionCache {

    val entries: MutableMap<Long, Task> = ConcurrentHashMap()

    override fun put(task: Task) {
        entries[task.id] = task
    }

    override fun evict(id: Long) {
        entries.remove(id)
    }
}

/**
 * `@Log` is rejected on Flow-returning functions: a stream has no single
 * success boundary. The subscription is logged once, then each collected item
 * is recorded manually at the collection site.
 */
@Singleton
class TaskStream @Inject constructor(
    private val dao: TaskDao,
    private val logger: ILogger,
) {

    fun watch(query: String = ""): Flow<Task> = flow {
        val rows = dao.search(query)
        for (task in rows) {
            emit(task)
            logger.record(
                action = TaskActions.STREAMED,
                entity = "Task",
                entityId = task.id.toString(),
                metadata = mapOf("title" to task.title, "priority" to task.priority.name),
                source = "TaskStream.watch",
                category = "diagnostics",
            )
        }
    }
}

/** Small independent contract used by the in-app TestKit checks. */
object BenchActions {
    const val CREATED = "note.created"
    const val RENAMED = "note.renamed"
    const val FAILED = "note.failed"
}

data class Note(val id: Long = 0, val title: String)

interface NoteBook {

    @Log(action = BenchActions.CREATED, metadata = [LogMeta("note.title")])
    suspend fun create(note: Note): Long

    @Log(
        action = BenchActions.RENAMED,
        entity = Note::class,
        entityId = "note.id",
        changes = [LogChange(field = "title", from = "note.title", to = "newTitle")],
    )
    suspend fun rename(note: Note, newTitle: String): Boolean

    @Log(action = BenchActions.FAILED, entity = Note::class, entityId = "note.id", onFailure = true)
    suspend fun explode(note: Note)
}

class InMemoryNoteBook : NoteBook {

    private val notes = HashMap<Long, Note>()
    private var next = 1L

    var lastThrown: Throwable? = null
        private set

    override suspend fun create(note: Note): Long {
        val id = next++
        notes[id] = note.copy(id = id)
        return id
    }

    override suspend fun rename(note: Note, newTitle: String): Boolean {
        if (note.id !in notes) return false
        notes[note.id] = note.copy(title = newTitle)
        return true
    }

    override suspend fun explode(note: Note) {
        val failure = IllegalStateException("kaboom")
        lastThrown = failure
        throw failure
    }
}
