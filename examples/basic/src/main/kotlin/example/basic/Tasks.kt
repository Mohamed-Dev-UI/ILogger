package example.basic

import io.ilogger.annotations.Log
import io.ilogger.annotations.LogChange
import io.ilogger.annotations.LogMeta

/** The domain model. Property chains in `@Log` select its members. */
data class Task(val id: Long, val title: String)

/** Canonical action codes live in one object; annotation values are constants. */
object TaskActions {
    const val CREATED = "task.created"
    const val RENAMED = "task.renamed"
    const val DELETED = "task.deleted"
}

/**
 * Annotate an interface, get audit instrumentation. The compiler generates
 * `internal class TaskDao_ILogger` plus the extension `TaskDao.logged(...)`.
 *
 * - entity inference: the single `Task` parameter ⇒ entity = "Task"
 * - entity id: `id` property ⇒ `task.id`; on [insert] the `Long` result = id
 * - metadata/changes are compile-time value selections — never reflection
 */
interface TaskDao {

    @Log(
        action = TaskActions.CREATED,
        metadata = [LogMeta("task.title")],
    )
    suspend fun insert(task: Task): Long

    @Log(
        action = TaskActions.RENAMED,
        metadata = [LogMeta("task.title")],
        changes = [LogChange(field = "title", from = "task.title", to = "newTitle")],
    )
    suspend fun rename(task: Task, newTitle: String): Boolean

    @Log(
        action = TaskActions.DELETED,
        before = true,      // START event before the operation
        onFailure = true,   // FAILURE event when the operation throws
    )
    suspend fun delete(task: Task)
}

/** The plain implementation — knows nothing about logging. */
class InMemoryTaskDao : TaskDao {
    private val tasks = LinkedHashMap<Long, Task>()

    override suspend fun insert(task: Task): Long {
        tasks[task.id] = task
        return task.id
    }

    override suspend fun rename(task: Task, newTitle: String): Boolean {
        return tasks.replace(task.id, task.copy(title = newTitle)) != null
    }

    override suspend fun delete(task: Task) {
        tasks.remove(task.id)
    }
}
