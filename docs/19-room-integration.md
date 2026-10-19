# 19. Room integration (`ilogger-room`)

Room DAOs are the canonical `@Log` host: Room 2.8.5 is KMP (JVM + Android),
and one annotated DAO interface serves both. Integration has **two
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
