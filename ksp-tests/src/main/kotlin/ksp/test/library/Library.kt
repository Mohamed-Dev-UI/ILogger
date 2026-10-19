package ksp.test.library

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogChange
import io.ilogger.annotations.LogLevel
import io.ilogger.annotations.LogMeta
import io.ilogger.core.LogValueExtractor

/** Test domain model. Every property is a candidate for compile-time selection. */
data class Task(
    val id: Long,
    val title: String,
    val priority: Int,
    val status: TaskStatus,
    val tags: List<String>,
)

enum class TaskStatus { OPEN, DONE, CLOSED }

/** Canonical action codes (annotation values are compile-time constants). */
object TaskActions {
    const val CREATED = "task.created"
    const val UPDATED = "task.updated"
    const val CLOSED = "task.closed"
    const val DELETED = "task.deleted"
    const val PURGED = "task.purged"
    const val FOUND_ID = "task.found.byId"
    const val BATCH_CREATED = "task.batchCreated"
    const val ASSIGNED = "task.assigned"
    const val SUMMARIZED = "task.summarized"
}

/**
 * Explicit extractor: converts a whole [Task] to a log-safe value. Requires a
 * public no-argument constructor — exactly what generated code instantiates.
 */
class TaskSummaryExtractor : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = value.title
}

/** Vararg parameters can only be converted as a whole (see `@Log` grammar). */
class BatchSizeExtractor : LogValueExtractor<Array<out Task>> {
    override fun extract(value: Array<out Task>): Any? = value.size
}

/**
 * The instrumented interface. Each member exercises one codegen path:
 *
 * - [insert]        — suspend, result-derived entity id, param-derived metadata
 * - [update]        — changes (before/after) and enum metadata
 * - [close]         — constant + context metadata, explicit entity id, category
 * - [delete]        — START (before) + SUCCESS on a Unit function
 * - [purge]         — FAILURE path with onFailure
 * - [findById]      — non-suspend function (recordNow)
 * - [insertAll]     — vararg + List result (batch: provable-no entity id)
 * - [assign]        — explicit actor override
 * - [summarize]     — explicit extractor metadata
 * - inline-prefixed — dispatch override
 * - passthrough (no `@Log`) — untouched delegation
 */
interface TaskRepository {

    @Log(
        action = TaskActions.CREATED,
        metadata = [LogMeta("task.title"), LogMeta("task.priority")],
        description = "Creates a task.",
    )
    suspend fun insert(task: Task): Long

    @Log(
        action = TaskActions.UPDATED,
        metadata = [LogMeta("task.status")],
        changes = [LogChange(field = "title", from = "task.title", to = "newTitle")],
    )
    suspend fun update(task: Task, newTitle: String): Boolean

    @Log(
        action = TaskActions.CLOSED,
        level = LogLevel.INFO,
        category = "workflows",
        metadata = [LogMeta("'closure'", name = "event"), LogMeta("context.requestId")],
    )
    suspend fun close(taskId: Long)

    @Log(action = TaskActions.DELETED, before = true)
    suspend fun delete(task: Task)

    @Log(action = TaskActions.PURGED, onFailure = true)
    suspend fun purge(task: Task)

    @Log(action = TaskActions.FOUND_ID)
    fun findById(taskId: Long): Task

    @Log(
        action = TaskActions.BATCH_CREATED,
        metadata = [LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")],
    )
    suspend fun insertAll(vararg tasks: Task): List<Long>

    @Log(action = TaskActions.ASSIGNED, actor = "userId")
    suspend fun assign(task: Task, userId: String)

    @Log(
        action = TaskActions.SUMMARIZED,
        metadata = [LogMeta("task", extractor = TaskSummaryExtractor::class, name = "summary")],
    )
    suspend fun summarize(task: Task): Int

    @Log(
        action = TaskActions.DELETED,
        schemaVersion = 2,
        category = "emergency",
        dispatch = DispatchMode.INLINE,
        metadata = [LogMeta("task.id")],
    )
    suspend fun inlineDelete(task: Task)

    suspend fun passthrough(value: Int): Int
}
