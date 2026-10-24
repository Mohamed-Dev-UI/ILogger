package ksp.test.runner

import ksp.test.library.Account
import ksp.test.library.ChangeTrackingRepo
import ksp.test.library.ReservedNamesRepository
import ksp.test.library.Task
import ksp.test.library.TaskRepository
import ksp.test.library.TaskStatus
import kotlin.coroutines.cancellation.CancellationException

/** In-memory implementation; the generated wrapper delegates into this. */
class InMemoryTaskRepository : TaskRepository {

    val inserted = ArrayList<Long>()

    override suspend fun insert(task: Task): Long = task.id.also { inserted.add(it) }

    override suspend fun update(task: Task, newTitle: String): Boolean {
        // Executed only through the wrapper: title is renamed in place.
        return true
    }

    override suspend fun close(taskId: Long) {}

    override suspend fun delete(task: Task) {}

    override suspend fun purge(task: Task) {
        throw IllegalStateException("db offline")
    }

    override suspend fun cancelDefault(taskId: Long) {
        throw CancellationException("cancel-default")
    }

    override suspend fun cancelLogged(taskId: Long) {
        throw CancellationException("cancel-logged")
    }

    override fun findById(taskId: Long): Task =
        Task(taskId, "found", 1, TaskStatus.OPEN, emptyList())

    override suspend fun insertAll(vararg tasks: Task): List<Long> = tasks.map { it.id }

    override suspend fun assign(task: Task, userId: String) {}

    override suspend fun summarize(task: Task): Int = task.priority

    override suspend fun inlineDelete(task: Task) {}

    override suspend fun passthrough(value: Int): Int = value + 1
}

class InMemoryChangeTrackingRepo : ChangeTrackingRepo {

    override suspend fun updateIfChanged(task: Task, newTitle: String): Boolean = true

    override suspend fun autoUpdate(oldTask: Task, updatedTask: Task): Boolean = true

    override suspend fun autoSave(task: Task): Task =
        task.copy(id = task.id + 1, title = "saved ${task.title}")

    override suspend fun autoUpdateWithExplicit(oldTask: Task, updatedTask: Task): Boolean = true

    override suspend fun renameAccount(oldAccount: Account, updatedAccount: Account): Boolean = true
}

class InMemoryReservedNamesRepository : ReservedNamesRepository {

    override suspend fun save(result: Task, iloggerResult: Task): Long = result.id

    override fun login(context: Task, actor: String): Boolean = actor.isNotBlank()

    override suspend fun fail(
        logger: String,
        iloggerDelegate: String,
        error: Throwable?,
        task: Task,
    ): Boolean {
        throw IllegalStateException("$logger/$iloggerDelegate/${error?.message}/${task.id}")
    }
}
