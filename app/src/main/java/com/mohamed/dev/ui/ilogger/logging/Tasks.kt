package com.mohamed.dev.ui.ilogger.logging

import io.ilogger.annotations.Log
import io.ilogger.annotations.LogChange
import io.ilogger.annotations.LogMeta
import io.ilogger.annotations.LogSensitive
import io.ilogger.annotations.SensitiveHandling
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object TaskActions {
    const val CREATED = "task.created"
    const val RENAMED = "task.renamed"
    const val FAILED = "task.failed"
}

data class Task(
    val id: Long,
    val title: String,
    /** Sensitive contract: only selectable with an explicit policy, e.g. HASH. */
    @LogSensitive(reason = "demo: values like this must be hashed before any sink")
    val passwordHash: String = "",
)

/**
 * Annotation-first instrumentation, same DX as the pure-Kotlin examples.
 * Inference: `entity`/entity-id from the domain parameter with an `id`
 * property; `result` values only exist on SUCCESS/COMPLETION events.
 */
interface TaskRepository {

    @Log(
        action = TaskActions.CREATED,
        entity = Task::class,
        metadata = [LogMeta("title")],
    )
    suspend fun create(title: String): Long

    @Log(
        action = TaskActions.RENAMED,
        entity = Task::class,
        metadata = [
            LogMeta("task.title"),
            // Name-refused by default (defense in depth); an explicit policy
            // unlocks it — the value is hashed, never sent raw to a sink.
            LogMeta("task.passwordHash", name = "passwordHashHashed", sensitive = SensitiveHandling.HASH),
        ],
        changes = [LogChange(field = "title", from = "task.title", to = "newTitle")],
    )
    suspend fun update(task: Task, newTitle: String): Boolean

    @Log(action = TaskActions.FAILED, entity = Task::class)
    suspend fun explode(taskId: Long)
}

class InMemoryTaskRepository : TaskRepository {

    private val mutex = Mutex()
    private val tasks = HashMap<Long, Task>()
    private var next = 1L

    override suspend fun create(title: String): Long = mutex.withLock {
        val id = next++
        tasks[id] = Task(id = id, title = title, passwordHash = "never-log-me-$id")
        id
    }

    override suspend fun update(task: Task, newTitle: String): Boolean = mutex.withLock {
        if (task.id in tasks) {
            tasks[task.id] = task.copy(title = newTitle)
            true
        } else {
            false
        }
    }

    override suspend fun explode(taskId: Long) {
        throw IllegalStateException("demo failure — the audit engine never swallows this")
    }
}
