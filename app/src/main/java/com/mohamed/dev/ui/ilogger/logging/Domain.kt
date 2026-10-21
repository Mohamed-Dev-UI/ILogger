package com.mohamed.dev.ui.ilogger.logging

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogDao
import io.ilogger.annotations.LogMeta
import io.ilogger.annotations.LogSensitive
import io.ilogger.core.LogValueExtractor

/** Canonical actions for the domain layer. Actions are public API: keep them stable. */
object TaskActions {
    const val CREATED = "task.created"
    const val RENAMED = "task.renamed"
    const val COMPLETED = "task.completed"
    const val DELETED = "task.deleted"
    const val FAILED = "task.failed"
    const val ARCHIVED = "task.archived"
    const val ESCALATED = "task.escalated"
    const val BATCH_CREATED = "task.batch.created"
    const val SUMMARIZED = "task.summarized"
    const val INLINE_AUDITED = "task.inline.audited"
    const val SEARCHED = "task.searched"
    const val STREAMED = "task.streamed"
    const val CACHE_WRITTEN = "cache.task.written"
    const val CACHE_EVICTED = "cache.task.evicted"
}

/** Low-level storage events, deliberately separate from the domain actions above. */
object StorageActions {
    const val INSERTED = "storage.task.inserted"
    const val BATCH_INSERTED = "storage.task.batch_inserted"
    const val RENAMED = "storage.task.renamed"
    const val COMPLETED = "storage.task.completed"
    const val ARCHIVED = "storage.task.archived"
    const val ASSIGNED = "storage.task.assigned"
    const val DELETED = "storage.task.deleted"
    const val FETCHED = "storage.task.fetched"
}

enum class TaskPriority { LOW, NORMAL, HIGH, URGENT }

enum class TaskStatus { OPEN, DONE, ARCHIVED }

/**
 * One room-backed task. `id` and Room's `@PrimaryKey` drive entity-id inference
 * for every `@Log` operation; `privateNote` is protected by a compile-time
 * sensitive-value contract (it can only be logged through an explicit policy).
 */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val status: TaskStatus = TaskStatus.OPEN,
    val assignee: String? = null,
    @LogSensitive(reason = "Private notes may contain personal data")
    val privateNote: String = "",
)

/**
 * Low-level storage surface. Annotating it with `@LogDao` also generates the
 * `AppDatabase_ILogged` facade used by [LoggingModule], so `taskDao()` is
 * returned already wrapped.
 *
 * Only methods that carry `@Log` are audited; `all()` and `search()` are plain
 * passthroughs because read traffic is not an accountable operation here.
 */
@LogDao
@Dao
interface TaskDao {

    @Insert
    @Log(
        action = StorageActions.INSERTED,
        metadata = [LogMeta("task.title"), LogMeta("task.priority")],
    )
    suspend fun insert(task: Task): Long

    @Insert
    @Log(
        action = StorageActions.BATCH_INSERTED,
        entity = Task::class,
        metadata = [LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")],
    )
    suspend fun insertAll(tasks: List<Task>): List<Long>

    @Query("UPDATE tasks SET title = :title WHERE id = :id")
    @Log(
        action = StorageActions.RENAMED,
        entity = Task::class,
        entityId = "id",
        metadata = [LogMeta("id", name = "taskId"), LogMeta("title", name = "newTitle")],
    )
    suspend fun rename(id: Long, title: String): Int

    @Query("UPDATE tasks SET status = 'DONE' WHERE id = :id")
    @Log(action = StorageActions.COMPLETED, entity = Task::class, entityId = "id")
    suspend fun complete(id: Long): Int

    @Query("UPDATE tasks SET status = 'ARCHIVED' WHERE id = :id")
    @Log(action = StorageActions.ARCHIVED, entity = Task::class, entityId = "id")
    suspend fun archive(id: Long): Int

    @Query("UPDATE tasks SET assignee = :assignee WHERE id = :id")
    @Log(
        action = StorageActions.ASSIGNED,
        entity = Task::class,
        entityId = "id",
        metadata = [LogMeta("assignee", name = "assignee")],
    )
    suspend fun assign(id: Long, assignee: String?): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    @Log(action = StorageActions.DELETED, entity = Task::class, entityId = "id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM tasks WHERE id = :id")
    @Log(action = StorageActions.FETCHED, entity = Task::class, entityId = "id")
    suspend fun byId(id: Long): Task?

    @Query("SELECT * FROM tasks ORDER BY id DESC")
    suspend fun all(): List<Task>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :query || '%' ORDER BY id DESC")
    suspend fun search(query: String): List<Task>
}

/**
 * Collection parameters can only be selected as a whole; an extractor converts
 * them into a log-safe primitive (here: the batch size).
 */
class BatchSizeExtractor : LogValueExtractor<List<Task>> {
    override fun extract(value: List<Task>): Any? = value.size
}

/** Explicit projection of a whole domain object: only the chosen summary leaks. */
class TaskSummaryExtractor : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = value.title.take(48)
}
