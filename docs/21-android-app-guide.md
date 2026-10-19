# 21. Android app guide (`:app` demo)

The demo app shows the full Android story: an Application-owned logger, a
generated wrapper, an in-app event viewer, and lifecycle-friendly flushing.

## Application-owned logger

```kotlin
class ILoggerApplication : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLogger.logger.flush()   // durability exactly when the OS may freeze us
    }
}

object AppLogger {
    private val _eventLines = MutableStateFlow<List<String>>(emptyList())
    val eventLines: StateFlow<List<String>> = _eventLines

    private val collectingSink = object : LogSink {
        override suspend fun write(event: LogEvent) {
            val line = DefaultTextEventCodec.encode(event)
            _eventLines.update { prev -> (listOf(line) + prev).take(50) }
        }
    }

    val logger: ILogger = ILogger.create {
        dispatch = DispatchMode.ASYNC   // UI threads never block
        sink(collectingSink)
        sink(ConsoleLogSink())          // logcat
    }
}
```

Points of interest:

- The **collector sink** caps at 50 lines — bounded memory even in the UI.
- `MutableStateFlow` updates come from the worker thread; Compose can collect
  it (`collectAsState`) — no manual main-thread hops needed for the demo.
- Manifest wiring: `android:name=".logging.ILoggerApplication"`.

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

On Android the recommended shape is: audit category → JSONL `TextFileSink`
with rotation into `context.filesDir`, and let an upload job tail the files —
the same routing the server examples use.

## What NOT to do on Android

- Do not `INLINE` in production dispatch; main-thread jank risk.
- Do not spawn application-level contexts per screen: global ambient context
  with explicit `withActor(...)` per interaction as the demo does.
- Do not log PII (location, contacts...) no matter what — the sensitive DSL
  will refuse at compile time but that is a guard, not a permission.
