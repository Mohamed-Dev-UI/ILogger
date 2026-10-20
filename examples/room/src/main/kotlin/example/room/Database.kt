package example.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogMeta
import io.ilogger.core.LogEvent

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

/**
 * The application's own log table — the *external* target the library's
 * [io.ilogger.room.RoomLogSink] writes into.
 *
 * Deliberately app-owned schema:
 * - the primary key is the event id, so redelivery (`ErrorMode.RETRY`,
 *   crashed-on-drain re-runs) is naturally idempotent with `@Insert(REPLACE)`;
 * - `payloadJson` stores the full lossless event (metadata, changes, failure
 *   details) via `ilogger-serialization`'s [io.ilogger.serialization.JsonEventCodec],
 *   while the flat columns keep the hot SQL queries (filter by level/action)
 *   index-friendly;
 * - columns here are an example — the sink works with ANY entity the app maps.
 */
@Entity(
    tableName = "log_events",
    indices = [Index("ts")],
)
data class LogEntity(
    @PrimaryKey val eventId: String,
    val ts: Long,
    val level: String,
    val action: String,
    val actionVersion: Int,
    val entity: String?,
    val entityId: String?,
    val actorId: String?,
    val outcome: String,
    val source: String?,
    val category: String?,
    val correlationId: String?,
    val causationId: String?,
    val payloadJson: String,
)

/** Maps one [LogEvent] into this app's [LogEntity] row shape. */
fun LogEvent.toLogRow(payloadJson: String): LogEntity = LogEntity(
    eventId = id,
    ts = timestampMillis,
    level = level.name,
    action = action,
    actionVersion = actionVersion,
    entity = entity,
    entityId = entityId,
    actorId = actor?.id,
    outcome = outcome.name,
    source = source,
    category = category,
    correlationId = correlationId,
    causationId = causationId,
    payloadJson = payloadJson,
)

/**
 * A plain DAO for the log table — **no `@Log` here** on purpose: the log
 * pipeline must not feed itself (generated wrappers on this DAO would grow
 * the table on every insert).
 */
@Dao
interface LogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<LogEntity>)

    /** Most recent rows — the demo prints these as proof of persistence. */
    @Query("SELECT * FROM log_events ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LogEntity>

    /** Retention: drop rows older than [cutoff] epoch millis. */
    @Query("DELETE FROM log_events WHERE ts < :cutoff")
    suspend fun purgeBefore(cutoff: Long): Int
}

@Database(entities = [TaskRow::class, LogEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): LogDao
}

