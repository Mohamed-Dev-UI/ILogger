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

## Open and abstract classes: subclass wrappers

`@Log` on an `open` or `abstract` class member generates a **subclass
wrapper** — there is no instance proxy in Kotlin, so wrapping is inheritance:

```kotlin
// For `open class AuditedCounter(private var count: Int)` with
// `@Log(action = "task.countedUp", entityId = "step") open fun bump(step: Int): Int`
internal class AuditedCounter_ILogger(
    logger: ILogger,
    count: Int,                     // every constructor parameter is forwarded
) : AuditedCounter(count) {         // forwarding via super-constructor args

    private val logger: ILogger = logger

    override fun bump(step: Int): Int {
        if (!logger.enabled) return super.bump(step)            // fast path
        val result = super.bump(step)                           // super, not a delegate field
        logger.recordNow(..., entityId = "3", source = "AuditedCounter.bump")
        return result
    }
}
```

Rules and limits:

- Only **open concrete members** are overridden. Overriding requires a
  `super` implementation to delegate to, so:
  - an **abstract member** is rejected at compile time (`@Log cannot
    instrument the abstract member '...'` — the wrapper has no
    implementation to delegate to). Annotate the concrete override instead,
    or move the operation into an interface;
  - a member that is not `open` is likewise rejected (the wrapper cannot
    override it).
- The generated wrapper is `internal` and its primary constructor takes
  `logger: ILogger` first, then forwards every superclass constructor
  parameter (varargs are spread). Subclass it directly:
  `class Child(logger: ILogger) : AuditedTaskBase_ILogger(logger)`.
- There is **no `logged()` extension for classes** — construct the wrapper
  instead. Interfaces remain the recommended path (composition, no
  constructor coupling); classes are supported where the type must stay a
  class.
- A **final class** with `@Log` members is rejected with an actionable
  diagnostic: mark the class `open`/`abstract`, move the operations into an
  interface, or instrument the call site with `ILogger.record(...)`.
- `INNER` classes and classes with `private` primary constructors are
  rejected (the wrapper could not be constructed or subclassed from the
  same scope).

## Top-level functions: adapter wrappers

`@Log` on a top-level function generates a file named after the function
(`<Name>_ILogger.kt`) containing either an adapter object or same-name
logger-first extensions:

```kotlin
// `@Log(...) suspend fun archive(task: Task): Long` — plain function:
public object Archive_ILogger {
    private val summaryExtractor = TaskSummaryExtractor()   // only if used

    public suspend fun archive(logger: ILogger, task: Task): Long {
        if (!logger.enabled) return archive(task)
        val result = archive(task)
        logger.record(..., entityId = result?.toString(), source = "archive")
        return result
    }
}

// `@Log(...) fun Task.tagCount(): Int` — extension function stays an extension,
// with the logger as the first parameter:
public fun Task.tagCount(logger: ILogger): Int {
    if (!logger.enabled) return this.tagCount()
    val result = this.tagCount()
    logger.recordNow(..., source = "tagCount")
    return result
}
```

- Plain functions get members on a public object; call shape:
  `Archive_ILogger.archive(logger, task)`.
- Extension functions keep their receiver and stay callable as extensions:
  `task.tagCount(logger)`.
- The delegate call uses the original function's plain name; the generated
  member and the original top-level function coexist as overloads (different
  parameter lists), so resolution is unambiguous.
- **Limitation**: the extension receiver cannot be referenced in annotation
  value expressions (`entityId = ...`, `LogMeta(...)` may only select
  parameters, `result`, `actor:`, `context.` heads). Extract the values you
  need from parameters or the result.

For all shapes (interface, class, top-level) the body anatomy below is
identical — only the delegation target differs (`iloggerDelegate.<fn>`,
`super.<fn>`, or the plain function call).

## Generics, `Result` and nullable returns

Type parameters flow through the wrapper verbatim — the wrapper class and its
`logged()` extension are generic:

```kotlin
// `interface GenericStore<T : Any>` with annotated members
internal class GenericStore_ILogger<T : Any>(
    private val iloggerDelegate: GenericStore<T>,
    private val logger: ILogger,
) : GenericStore<T> by iloggerDelegate

public fun <T : Any> GenericStore<T>.logged(logger: ILogger): GenericStore<T>
```

A `@Log` on a **generic function** (`fun <R : Any> transform(...)`) emits a
generic override with the same bounds. A type parameter itself is never
treated as a domain entity — `T` exposes no properties, so entity inference
yields nothing and id/metadata selections must use parameters, constants or
extractors (selecting a property *of* a type parameter is rejected as
unresolvable).

`Result<T>` and nullable returns are unwrapped before inference and result
selection: a `Result<Task>` return yields `entity = "Task"` and
`entityId = "result.id"` compiles to `result.getOrNull()?.id?.toString()`
(the event is emitted only on success, where the value is present, so
`getOrNull` never loses information). `Task?` returns render plain safe-call
chains (`result?.id?.toString()`).

## Flow-returning functions: stream instrumentation

`@Log` on a function returning `Flow<T>` instruments the **stream lifecycle**,
not individual values — cold wrapping via `onStart`/`onCompletion`, no
upstream operator cost when the logger is disabled:

```kotlin
override fun watchAll(): Flow<Task> {
    val upstream = iloggerDelegate.watchAll()
    if (!logger.enabled) return upstream              // fast path: raw upstream
    return upstream
        .onStart  { logger.record(..., outcome = Outcome.START, source = "FlowingRepo.watchAll") }
        .onCompletion { cause ->
            when {
                cause == null -> logger.record(..., entity = "Task")            // SUCCESS
                cause is CancellationException -> { /* SKIPPED by default */ }
                else -> {
                    try {
                        logger.record(..., outcome = Outcome.FAILURE, failure = cause)
                    } catch (dispatchError: Throwable) {
                        cause.addSuppressed(dispatchError)   // never masks the stream error
                    }
                    throw cause                              // propagates verbatim
                }
            }
        }
}
```

Semantics, pinned by tests:

- **START** at subscription time (`before = true`); **SUCCESS** on normal
  completion; **FAILURE** on stream error (dispatch failures are suppressed
  onto the original, which is rethrown unchanged). The stream error is never
  swallowed and never replaced.
- **Cancellation is control flow, not failure**: by default a cancelled
  collection records nothing beyond an already-emitted START. With
  `onCancellation = OnCancellation.LOG` the terminal cancellation is recorded
  as a FAILURE event marked cancelled, via `recordNow` with `ASYNC` dispatch —
  never suspending in a cancelled coroutine.
- There is no single result value, so `result.*` selections are rejected at
  compile time (the diagnostic points to parameters/constants). Entity
  inference still unwraps the element type: `Flow<Task>` infers
  `entity = "Task"`.
- Hook lambdas capture the wrapper through qualified receivers
  (`this@Wrapper.logger`) because the `FlowCollector` receiver shadows `this`;
  context is read per event (`LogContexts.current()`), so
  `withContextAttributes` around `collect` is visible to the terminal event.

The same lifecycle is available **without codegen** for arbitrary streams:

```kotlin
// ilogger-core StreamAdapters.kt
fun <T> Flow<T>.loggedFlow(logger: ILogger, action: String, ...): Flow<T>
fun <T> ReceiveChannel<T>.loggedChannel(logger: ILogger, action: String, ...): Flow<T>
fun <T> Sequence<T>.loggedSequence(logger: ILogger, action: String, ...): Sequence<T>
```

`loggedFlow`/`loggedChannel` use the identical START/SUCCESS/FAILURE/
cancellation semantics; `loggedSequence` is the pull-based analogue (START
when iteration begins, SUCCESS when exhausted, FAILURE on iterator errors,
cancellation per `OnCancellation`). All adapters short-circuit to the raw
stream when `logger.enabled` is false.

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

(The real generated code uses the same shapes with collision-proof names:
locals are `iloggerResult`, `iloggerCtx`, `iloggerError`, ... — renamed with a
trailing `_` if your parameters use those names — and wrapper state is read
through `this.logger` / `this.iloggerDelegate`, so parameters named `logger`,
`result`, `context`, `actor`, or `error` never change behavior. Source
expressions still resolve `result`/`actor`/`context` heads specially; there is
no syntax to select a parameter that shares one of those names.)
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

- The wrapper stays `internal` (interfaces and classes); **only** `logged()`
  and the top-level `<Name>_ILogger` wrappers are public API. App modules do
  not see any extra public surface besides those.
- Generated source is formatted (KotlinPoet) and — contractually — never
  hand-edited; regenerate instead.
- The wrapper class exists per *interface* (per-file grouping), not per
  implementation class: implementations are attached through the extension.
  Class wrappers exist per *class* and are subclassed; top-level wrappers
  are grouped per file into one object/function file per declaring file.
