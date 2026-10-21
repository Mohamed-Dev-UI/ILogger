# 22. Generated code guide

The processor generates, per annotated interface, two public-API shapes and
one internal class — all plain Kotlin you can read in
`build/generated/ksp/main/kotlin/`.

## The shapes

```kotlin
// internal, performs the actual instrumentation
internal class TaskDao_ILogger(
    iloggerDelegate: TaskDao,     // the wrapped real implementation
    logger: ILogger,
) : TaskDao by iloggerDelegate    // interface delegation: pass-through by default

// the only extension your call sites touch
public fun TaskDao.logged(logger: ILogger): TaskDao = TaskDao_ILogger(this, logger)
```

`KModifier.PRIVATE` cannot correlate with a primary constructor parameter in
KotlinPoet, hence the property↔parameter correlation trick: adding a
same-named `PropertySpec` with `initializer("paramName")` produces
`private val` fields inside the primary constructor (used for both
`iloggerDelegate` and `logger` fields).

Why the name is `iloggerDelegate` (not `delegate`): KotlinPoet backtick-
escapes the keyword `delegate` in generated property/parameter names; we
avoid the escape entirely.

## Function body anatomy (for annotated members)

For `suspend fun insert(task: Task): Long` with `@Log(action = CREATED)` and
default options, the generated body (verified shape) is:

```kotlin
override suspend fun insert(task: Task): Long {
    if (!logger.enabled) return iloggerDelegate.insert(task)   // fast path
    val result = iloggerDelegate.insert(task)                  // delegate first
    logger.record(          // suspend path: ambient context (coroutine!)
        action = "task.created",
        actionVersion = 1,
        level = LogLevel.AUDIT,
        entity = "Task",
        entityId = result?.toString(),   // inference applied
        metadata = mapOf("title" to task?.title),
        source = "TaskRepository.insert",
    )
    return result
}
```

With `onFailure = true` the delegate call and SUCCESS record move inside a
`try`, and the catch records the FAILURE event then **rethrows verbatim**:

```kotlin
try {
    val result = iloggerDelegate.purge(task)
    logger.record(..., action = "task.purged", entity = "Task",
                  entityId = task?.id?.toString(), source = "TaskRepository.purge")
} catch (error: Throwable) {
    try {
        logger.record(..., outcome = Outcome.FAILURE, failure = error, source = "/")
    } catch (dispatchError: Throwable) {
        error.addSuppressed(dispatchError)   // dispatch failures never mask the real error
    }
    throw error                              // NEVER swallowed
}
```

Key rules of every generated body (exact order):

1. **Fast path**: `if (!logger.enabled) return iloggerDelegate.<fn>(...)`.
2. Optional `val logCtx0 = LogContexts.current()` (suspend) /
   `LogContexts.currentThread()` (non-suspend) — emitted only when the
   annotations need context/actor values (`context.*`, `actor.*`, explicit
   actor override).
3. Optional START event **before** the delegate call, when `before = true`
   (result-derived values are omitted on START/FAILURE).
4. The delegate call; with `onFailure = true` inside a `try`.
5. Optional SUCCESS event (default) with `metadata` + `changes` (changes on
   SUCCESS only), then `return result`.
6. FAILURE catch (with `onFailure = true`): record, suppress dispatch errors
   onto the original, rethrow the original.
7. **Parameter dispatch**: `suspend` members call `record(...)`, non-suspend
   members call `recordNow(...)` with an explicitly emitted
   `DispatchMode.<MODE>` (its runtime default is `ASYNC`, not the engine's);
   suspend members emit `dispatch` only when overriding `DEFAULT`.
8. Member unrelated to audit — not annotated (passthrough in the fixtures) —
   delegates untouched via interface delegation: zero generated overhead.

## The `@LogDao` database facade

`@LogDao` adds one generated file per `@Database` that exposes at least one
opted-in DAO:

```kotlin
public class AppDatabase_ILogged internal constructor(
    private val db: AppDatabase,
    private val logger: ILogger,
) {
    public val database: AppDatabase get() = db
    public fun taskDao(): TaskDao = db.taskDao().logged(logger)  // opted in
    public fun logDao(): RoomLogDao = db.logDao()                // raw
}

public fun AppDatabase.logged(logger: ILogger): AppDatabase_ILogged =
    AppDatabase_ILogged(db = this, logger = logger)
```

The facade only changes where the adapter comes from; `TaskDao_ILogger` still
does the recording and still requires `@Log` on each method. Accessors for
DAOs without `@LogDao(enabled = true)` are delegated unchanged.

`@LogDao(hilt = true)` adds a second generated file:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
public object AppDatabase_ILogged_HiltModule {
    @Provides @Singleton
    public fun taskDao(db: AppDatabase, logger: ILogger): TaskDao =
        db.logged(logger).taskDao()
}
```

Hilt discovers that module in the consumer's compilation; the library itself
has no Hilt dependency.

`@LogDao(koin = true)` generates the Koin equivalent:

```kotlin
public object AppDatabase_ILogged_KoinModule {
    public val module: Module = module {
        single<TaskDao> {
            get<AppDatabase>().logged(get<ILogger>()).taskDao()
        }
    }
}
```

The definitions resolve the database and logger from Koin; the library has no
Koin dependency either.

## Generated code hygiene

- The wrapper stays `internal`; **only** `logged()` is public API. App
  modules do not see any extra public surface besides the extension.
- Generated source is formatted (KotlinPoet) and — contractually — never
  hand-edited; regenerate instead.
- The wrapper class exists per *interface* (per-file grouping), not per
  implementation class: implementations are attached through the extension.
