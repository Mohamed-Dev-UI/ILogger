package example.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogMeta

/**
 * Room entity. The ilogger processor recognizes `@PrimaryKey` by qualified
 * name (without any Room dependency of its own) — this property becomes the
 * entity-id source for non-insert operations.
 */
@Entity(tableName = "tasks")
data class TaskRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val completed: Boolean = false,
)

object TaskActions {
    const val CREATED = "task.created"
    const val COMPLETED = "task.completed"
    const val FETCHED = "task.fetched"
    const val DELETED = "task.deleted"
}

/**
 * Room DAO instrumented with `@Log` side-by-side with Room annotations.
 *
 * Entity-id inference:
 * - [insert] — Room `@Insert` returning `Long`: the result **is** the row id
 *   (insert-shaped detection covers both `@Insert`/`@Upsert` annotation and
 *   insert-prefixed names).
 * - the other operations — the single id-typed parameter is provable.
 */
@Dao
interface TaskDao {

    @Insert
    @Log(
        action = TaskActions.CREATED,
        metadata = [LogMeta("task.title")],
    )
    suspend fun insert(task: TaskRow): Long

    @Query("UPDATE tasks SET completed = 1 WHERE id = :id")
    @Log(action = TaskActions.COMPLETED, entity = TaskRow::class)
    suspend fun complete(id: Long)

    @Query("SELECT * FROM tasks WHERE id = :id")
    @Log(action = TaskActions.FETCHED, entity = TaskRow::class)
    suspend fun byId(id: Long): TaskRow?

    @Query("DELETE FROM tasks WHERE id = :id")
    @Log(action = TaskActions.DELETED, entity = TaskRow::class)
    suspend fun delete(id: Long)
}

@Database(entities = [TaskRow::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}
