# 19. Room integration (`ilogger-room`)

Room DAOs are the canonical `@Log` host: Room 2.8.5 is KMP (JVM + Android),
and one annotated DAO interface serves both. Integration has **three
complementary layers**:

## Layer 1 — DAO instrumentation (processor-side, no extra module needed)

ilogger-ksp detects Room annotations **by qualified name**:

```kotlin
@Dao
interface TaskDao {

    @Insert
    @Log(action = TaskActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: TaskRow): Long

    @Query("UPDATE tasks SET completed = 1 WHERE id = :id")
    @Log(action = TaskActions.COMPLETED, entity = TaskRow::class)
    suspend fun complete(id: Long)

    @Query("SELECT * FROM tasks WHERE id = :id")
    @Log(action = TaskActions.FETCHED, entity = TaskRow::class)
    suspend fun byId(id: Long): TaskRow?
}
```

What the processor does:

- `@Insert` returning `Long` → the **result is the row id**
  (`TaskRow(1)` in the event; note NOT the constructor's `id = 0`).
- `@PrimaryKey` property → entity-id source for non-insert operations
  (`complete(id)`, `byId(id)`).
- Room-shaped list upserts/`vararg`: provable-no entity id + entity name +
  optional extractor (see [10-extractors.md](10-extractors.md)).

## Layer 2 — database lifecycle events (`:ilogger-room`)

```kotlin
implementation(project(":ilogger-room"))

val db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .withAuditCallback(logger, label = dbFile.name)   // <- this module
    .build()

val dao = db.taskDao().logged(logger)                  // <- generated wrapper
```

Events (lifecycle order, both layers in one example):

```
[AUDIT] room.database.created Database(ilogger-room-example.db) via androidx.room {label=...}
[INFO]  room.database.opened  Database(ilogger-room-example.db) via androidx.room {label=...}
[AUDIT] task.created actor=u-42(Grace) TaskRow(1) via TaskDao.insert {title=...}
```

`room.database.destructiveMigration` (WARN, `outcome = FAILURE`) also exists
for destructive schema changes.

## Layer 3 — store events in your own Room table (`ilogger-room`)

`RoomLogSink` sends every event into the application's **own** log table (an
external `LogEntity`): the schema, columns and indexes stay app-owned, the
library only bridges event batches into your DAO — no reflection, no
library-managed schema, no forced migrations.

Define the entity and DAO anywhere in your Room database (do **not** annotate
the log DAO with `@Log` — the pipeline must not feed itself):

```kotlin
@Entity(tableName = "log_events", indices = [Index("ts")])
data class LogEntity(
    @PrimaryKey val eventId: String,   // LogEvent.id → redelivery is idempotent
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
    val payloadJson: String,           // full lossless event via JsonEventCodec
)

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<LogEntity>)
}
```

Wire the sink with `DispatchMode.BUFFERED` so database writes leave the hot
path and arrive as real batches:

```kotlin
val logger = ILogger.create {
    dispatch = DispatchMode.BUFFERED
    batchSize = 8
    sink(RoomLogSink { events ->
        logDao.insertAll(events.map { it.toLogRow(JsonEventCodec.encode(it)) })
    })
}
```

Semantics: `writeAll` is forwarded **whole** to the lambda (one transaction
per batch); a throwing lambda propagates to the configured `ErrorMode`
(`REPLACE` on the event-id primary key makes retries idempotent); `flush()`/
`close()` drain the buffered queue. The full runnable version — including the
`CompletableDeferred` readiness gate that lets *lifecycle events* persist into
the table they are measured on (the logger exists before `Room.build()`, the
DAO only after) — is `examples/room`; the demo prints the `log_events` rows
back as proof.

## Platform notes

- `withAuditCallback` uses the common `RoomDatabase.Callback` over
  `SQLiteConnection` (JVM + Android, no SupportSQLiteDatabase dependency).
- Room's `setQueryCallback` is Android-source-only upstream and deliberately
  NOT wrapped, so this module stays multiplatform-clean. Query-text
  telemetry is default-excluded anyway (privacy posture:
  [01-overview.md](01-overview.md)).

## Privacy posture

Nothing derived from SQL text or bound arguments is ever recorded by this
module. Entity values flow only through the DAO layer's `@Log` metadata
selection.
