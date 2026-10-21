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

### Automatic DAO access with `@LogDao`

Marking the DAO opts it into the generated **database facade**, so call sites
never repeat `.logged(logger)`:

```kotlin
@LogDao
@Dao
interface TaskDao { /* @Log methods as above */ }

@Database(entities = [TaskRow::class, RoomLogEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): RoomLogDao
}

val daos = db.logged(logger)   // generated: AppDatabase_ILogged
val dao = daos.taskDao()       // already TaskDao_ILogger
```

The facade exposes every DAO accessor declared on the database. Opted-in DAOs
are wrapped; all others (including `@LogDao(enabled = false)`) pass through raw.
Only methods with an explicit `@Log` are recorded — the annotation changes how
the adapter is obtained, never what an action means. The explicit
`db.taskDao().logged(logger)` form keeps working, and `daos.database` exposes
the underlying database for transactions.

The `@LogDao` interface must have its `@Log` methods in the same compilation
unit that generates the facade (the usual single-module setup).

### Optional Hilt binding

`@LogDao(hilt = true)` generates a Hilt module in the consumer's compilation,
so injection already returns the wrapped DAO — no `db.logged(logger)` at the
injection point or call site:

```kotlin
@LogDao(hilt = true)
@Dao interface TaskDao { /* @Log methods */ }

@HiltAndroidApp class App : Application()

class TaskRepository @Inject constructor(private val taskDao: TaskDao) {
    suspend fun add(task: TaskRow) = taskDao.insert(task)   // audited
}
```

The generated `<Database>_ILogged_HiltModule` is
`@InstallIn(SingletonComponent::class)` and provides each opted-in DAO as a
`@Singleton` built from
`db.logged(logger).taskDao()`. Hilt is not a dependency of any `ilogger-*`
module: if its annotations are absent, the processor reports a fixable
diagnostic and skips the module. The database and DAO must be public.
Exposing the same DAO from two `@Database` classes is rejected with a
duplicate-binding diagnostic; keep `hilt = true` on one database's DAO, or
bind it manually.

### Optional Koin binding

`@LogDao(koin = true)` generates `<Database>_ILogged_KoinModule` with a
`Module` property:

```kotlin
@LogDao(koin = true)
@Dao interface TaskDao { /* @Log methods */ }

startKoin {
    modules(AppDatabase_ILogged_KoinModule.module, appModule)
}

class TaskRepository(private val taskDao: TaskDao) {
    suspend fun add(task: TaskRow) = taskDao.insert(task)   // audited
}
```

Each definition resolves `AppDatabase` and `ILogger` from Koin and returns
`db.logged(logger).taskDao()`, so both must be registered in the application
module. Koin is not a dependency of any `ilogger-*` module: absent Koin yields
a fixable diagnostic and no generated module. Public database/DAO types are
required, and duplicate bindings across `@Database` classes are rejected just
like the Hilt case.

## Layer 2 — database lifecycle events (`:ilogger-room`)

```kotlin
implementation(project(":ilogger-room"))

// JVM hosts need a driver; Android apps can omit setDriver() and use Room's
// default framework path (it owns the connection pool).
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

## Layer 4 — one-line persistence with the shipped table (`ilogger-room`)

When you do not need a custom row shape, the library ships the entity and DAO
and the whole wiring is one builder call. Add the entity to your database and
expose the DAO:

```kotlin
@Database(entities = [TaskRow::class, RoomLogEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): RoomLogDao
}

val db = /* your normal Room setup — unchanged */

val logger = ILogger.create {
    sink(ConsoleLogSink())
    roomLogs(db.logDao(), retention = RoomLogRetention.keepDays(30))
}
```

The library owns the `ilogger_events` table contract (`RoomLogSchema`), not
your database: entities, version, driver, migrations, encryption and file name
stay yours. `roomLogs` maps each event into the shipped columns, stores the
full event in `payload` (JSON via `JsonEventCodec` by default), forwards real
engine batches as one `@Insert`, and — when a policy is supplied — sweeps
rows older than the retention window after successful inserts. Retention is
off by default.

Lifecycle events (`room.database.created/opened/destructiveMigration`) need
the logger **before** `build()`; use a target and bind it after:

```kotlin
val logs = RoomLogTarget(retention = RoomLogRetention.keepDays(30))
val logger = ILogger.create { sink(logs) }
val db = Room.databaseBuilder<AppDatabase>(name = path)
    .withAuditCallback(logger)
    .build()
logs.bind(db.logDao())        // queued lifecycle events drain here
```

Adding the table to an existing database is a normal Room migration:

```kotlin
val db = Room.databaseBuilder<AppDatabase>(context, "app.db")
    .addMigrations(RoomLogSchema.migration(from = 4, to = 5))
    .build()
```

### Retention mechanics

`RoomLogRetention(keepMillis, sweepEveryMillis)` — or the convenience
`RoomLogRetention.keepDays(days, sweepEveryHours = 24)`:

- **Sweeping runs inside the dispatch worker**, right after a successful
  insert — no extra coroutine, timer or scheduler.
- At most **one** `purgeBefore` per `sweepEveryMillis` window: an in-memory
  timestamp guards the cadence.
- `purgeBefore` deletes rows with `ts < now - keepMillis` and returns the
  count; the sweep is best-effort — failures go to `RoomLogTarget`'s optional
  `onError` callback and never fail the write that triggered them.
- Retention is **opt-in**: no policy → nothing is ever purged.

## Wiring approaches compared

| Approach | Schema owner | Wiring | Simplicity | Recommended when |
|---|---|---|---|---|
| `roomLogs(db.logDao(), retention = ...)` | Library (`ilogger_events`) | One builder call | Simplest | **🏆 Default** — no custom columns needed |
| `RoomLogTarget` + `bind(dao)` | Library | Target before `build()`, bind after | Small extra step | Logger must exist before the database (lifecycle events) |
| `RoomLogSink { events -> ... }` | Yours (`log_events`) | Lambda bridge, one transaction per batch | Medium — you own columns/mapping | You need your own indexes/row shape |
| `onEvents { ... }` trigger | Yours | Failure-isolated observer | Medium | You also want non-persistence reactions (metrics/alerts) |

All four deliver identical durability semantics: real engine batches,
`REPLACE`-on-event-id idempotency, engine `ErrorMode` retry for AUDIT/ERROR.

### Custom rows via triggers

If you already own a log entity, keep it and use the trigger hook instead of a
hand-written sink — it receives whole engine batches and its failures are
isolated from the producer:

```kotlin
ILogger.create {
    onEvents { events ->
        myLogDao.insertAll(events.map { it.toMyLogRow() })
    }
}
```

| Need | Use |
|---|---|
| Store everything, no mapping code | `roomLogs(db.logDao())` |
| Lifecycle events before `build()` | `RoomLogTarget` + `bind` |
| Your own columns/entity | `RoomLogSink { ... }` or `onEvents { ... }` |
| Read/purge the rows | `RoomLogDao.recent` / `purgeBefore` |
| Android with Hilt | the `LoggingModule` recipe in [21-android-app-guide.md](21-android-app-guide.md) |

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
