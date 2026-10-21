# 21. Android app guide (`:app` demo)

The demo app shows the production shape: Hilt owns the graph, Room persists
every event through the shipped `RoomLogEntity`/`RoomLogDao`, and Compose shows
a bounded live view of events.

## Hilt wiring (recommended)

ILogger ships no Hilt annotations or dependencies — DI stays an application
concern. The whole wiring is a normal module; the graph direction is
`AppDatabase -> ILogger -> consumers`, so there is no cycle:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        // No explicit driver on Android: Room's default framework path uses
        // its own connection pool and is safe across the logger's worker and
        // the UI. (JVM hosts must supply a driver — there is no framework
        // SQLite there.)
        Room.databaseBuilder<AppDatabase>(context, "app.db").build()

    @Provides @Singleton
    fun logger(db: AppDatabase, buffer: EventBuffer): ILogger = ILogger.create {
        dispatch = DispatchMode.ASYNC       // UI threads never block
        sink(buffer.sink)                   // bounded live view
        sink(ConsoleLogSink())              // logcat
        roomLogs(db.logDao(), retention = RoomLogRetention.keepDays(30))
    }

    @Provides fun logDao(db: AppDatabase): RoomLogDao = db.logDao()

    @Provides @Singleton
    fun taskRepository(logger: ILogger): TaskRepository =
        InMemoryTaskRepository().logged(logger)
}
```

`AppDatabase` is the app's own class. Adding the library log table is one entity
and one accessor; Room generates `RoomLogDao_Impl` in this module:

```kotlin
@Database(entities = [TaskRow::class, RoomLogEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): RoomLogDao
}
```

## Automatic DAO logging (`@LogDao`)

Annotate a DAO interface with `@LogDao` and the processor generates a facade
for its database. Wrap the database once; every opted-in DAO accessor comes
back instrumented:

```kotlin
@LogDao
@Dao
interface TaskDao {
    @Log(action = TaskActions.CREATED)
    @Insert suspend fun insert(task: TaskRow): Long
}

val daos = db.logged(logger)      // generated: AppDatabase_ILogged
val taskDao = daos.taskDao()      // already wrapped with .logged(logger)
```

Only methods carrying `@Log` are recorded — actions are never derived from
method names. `@LogDao(enabled = false)` keeps the explicit adapter but makes
the facade return the raw DAO. `taskDao().logged(logger)` remains available.

For Hilt, add `hilt = true`; the processor generates
`<Database>_ILogged_HiltModule` and injection returns the wrapped DAO directly:

```kotlin
@LogDao(hilt = true)
@Dao interface TaskDao { /* @Log methods */ }

class TaskRepository @Inject constructor(private val taskDao: TaskDao) {
    // taskDao is TaskDao_ILogger — no db.logged(logger) needed
}
```

The binding is a `@Singleton` provider built from `db.logged(logger).taskDao()`.
No Hilt dependency leaks into the library; Hilt must already be on the app's
classpath, and the database/DAO must be public.

Koin uses the same opt-in with `koin = true`:

```kotlin
@LogDao(koin = true)
@Dao interface TaskDao { /* @Log methods */ }

startKoin { modules(AppDatabase_ILogged_KoinModule.module, appModule) }
```

The generated `Module` resolves the database and `ILogger` from Koin; register
both in `appModule`. No Koin dependency leaks into the library.

If lifecycle telemetry is wanted, provide a `RoomLogTarget` before the logger
and bind the DAO inside the database provider:

```kotlin
@Provides @Singleton
fun roomLogTarget(): RoomLogTarget = RoomLogTarget(retention = RoomLogRetention.keepDays(30))

@Provides @Singleton
fun logger(target: RoomLogTarget): ILogger = ILogger.create {
    dispatch = DispatchMode.ASYNC
    sink(target)
}

@Provides @Singleton
fun database(
    @ApplicationContext context: Context,
    logger: ILogger,
    target: RoomLogTarget,
): AppDatabase = Room.databaseBuilder<AppDatabase>(context, "app.db")
    .withAuditCallback(logger)
    .build()
    .also { target.bind(it.logDao()) }
```

Flush when the OS may freeze the process; the injected singleton is the same
logger the UI uses:

```kotlin
@HiltAndroidApp
class ILoggerApplication : Application() {
    @Inject lateinit var logger: ILogger

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        logger.flush()
    }
}
```

## UI event buffer

The display sink is a singleton so it can be injected into the activity. It
caps at 50 lines — bounded memory even in the UI — and updates a `StateFlow`
from the logger's worker thread; Compose collects it directly:

```kotlin
@Singleton
class EventBuffer @Inject constructor() {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    val sink: LogSink = object : LogSink {
        override suspend fun write(event: LogEvent) {
            _lines.update { previous ->
                (listOf(DefaultTextEventCodec.encode(event)) + previous).take(50)
            }
        }
    }
}
```

Manifest wiring is unchanged: `android:name=".logging.ILoggerApplication"`.

## Instrumented test: the real graph on a device

Unit tests cover the engine, but device tests are what prove the Android wiring
(Room driver, Hilt scoping, generated wrapper) actually works. Hilt 2.59+
rejects tests that run under an `@HiltAndroidApp` application, so the test
runner boots `HiltTestApplication` instead; Hilt still aggregates the
production `@InstallIn(SingletonComponent::class)` modules, so the injected
`ILogger`, `AppDatabase`, `EventBuffer` and `TaskRepository` are the real
bindings:

```kotlin
class ILoggerTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context) =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
```

```kotlin
// app/build.gradle.kts
android.defaultConfig.testInstrumentationRunner =
    "com.mohamed.dev.ui.ilogger.logging.ILoggerTestRunner"
```

[`RoomLoggingInstrumentedTest`](../app/src/androidTest/java/com/mohamed/dev/ui/ilogger/logging/RoomLoggingInstrumentedTest.kt)
then emits through the injected repository, flushes the logger, and asserts the
row through both the injected DAO and a second connection to the same database
file — so a stubbed sink or in-memory fake cannot pass:

```bash
./gradlew :app:connectedDebugAndroidTest
# or just this test:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mohamed.dev.ui.ilogger.logging.RoomLoggingInstrumentedTest
```

## Annotation-first repositories in the app

Same as any other module: `ksp(project(":ilogger-ksp"))` on the Android
module (tested with AGP 9.4.1) and the usual shape:

```kotlin
interface TaskRepository {

    @Log(action = TaskActions.CREATED, entity = Task::class,
         metadata = [LogMeta("title")])
    suspend fun create(title: String): Long

    @Log(action = TaskActions.RENAMED, entity = Task::class,
         metadata = [LogMeta("task.title")],
         changes = [LogChange(field = "title", from = "task.title", to = "newTitle")])
    suspend fun update(task: Task, newTitle: String): Boolean

    @Log(action = TaskActions.FAILED, entity = Task::class)
    suspend fun explode(taskId: Long)
}
```

The Hilt module wraps the implementation once with `.logged(logger)`, so no
screen or view model has to remember the wrapper.

## Sensitive values on mobile

```kotlin
data class Task(
    val id: Long,
    val title: String,
    @LogSensitive val passwordHash: String = "",
)

LogMeta("task.passwordHash", name = "passwordHashHashed",
        sensitive = SensitiveHandling.HASH)
```

The COMPILE TIME refusal list (defense in depth over names like
`accessToken`) is fully active on Android sources too.

## Should your audits go to disk?

Storage options compared for a mobile host:

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Room (`roomLogs`) | Queryable history, indexes on `ts`/`level`/`action`, retention sweep built-in | Needs a database (usually already present in an app) | **🏆 Recommended** for apps that display or query audits |
| JSONL `TextFileSink` + rotation | Append-only forensics; trivially shipped off-device | Row-level queries are manual (grep/tail) | Right default when the app only ships files |
| Both (Room + file) | Field forensics + in-app viewer | Two destinations to size (`bufferSize`!) | Only when a real compliance need exists |

The demo persists to Room (`roomLogs`), which is the right default when the app
needs to query or display events. For append-only forensics with rotation and
off-device upload, the alternative shape is: audit category → JSONL
`TextFileSink` with rotation into `context.filesDir`, and let an upload job tail
the files — the same routing the server examples use.

### Flushing the audit buffer on Android

| Trigger | How | Trade-off | Verdict |
|---|---|---|---|
| `Application.onTrimMemory` | `logger.flush()` (demo) | Fires before freeze/kill scenarios | **🏆 Recommended** — cheap, official lifecycle hook |
| `Activity.onDestroy` | `logger.flush()` | Per-screen churn; flushes too often | Only for single-activity legends |
| Periodic WorkManager job | flush + upload JSONL | Durable upload cadence; drains the pipeline | Pair with the file path for off-device shipping |

## What NOT to do on Android

- Do not `INLINE` in production dispatch; main-thread jank risk.
- Do not spawn application-level contexts per screen: global ambient context
  with explicit `withActor(...)` per interaction as the demo does.
- Do not log PII (location, contacts...) no matter what — the sensitive DSL
  will refuse at compile time but that is a guard, not a permission.
