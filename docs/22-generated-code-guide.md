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

For `suspend fun insert(task: Task): Long` with `@Log(action = CREATED)`:

```kotlin
override suspend fun insert(task: Task): Long {
    try {
        val result = iloggerDelegate.insert(task)
        logger.record(          // suspend path: ambient context (coroutine!)
            action = "task.created",
            level = LogLevel.AUDIT,
            entity = "Task",
            entityId = result.toString(),   // inference applied
            metadata = mapOf("title" to task.title),
            source = "TaskDao.insert",
        )
        return result
    } catch (e: Throwable) {
        logger.record(          // failure event with structured payload
            action = "task.created",
            result = null, ..., failure = e, outcome = Outcome.FAILURE)
        throw e                 // NEVER swallowed
    }
}
```

Key rules of every generated body:

1. **Fast path**: functions whose instrument state (feature disabled) skip
   straight to `iloggerDelegate.<fn>(...)`.
2. **Parameter dispatch**: `suspend` members call `record(...)` (ambient
   context from the coroutine), non-suspend members call
   `recordNow(...)`; an explicit `dispatch` is emitted for events with
   `dispatch != DEFAULT`.
3. **Explicit fail-free catch**: failure path records then `throw e`.
4. **START events** come before the try, when `before = true`.
5. Member **unrelated to audit** — not annotated (`passthrough` in the
   fixtures) delegates untouched by delegation — the interface delegation
   guarantees zero generated overhead for unannotated members.

## Generated code hygiene

- The wrapper stays `internal`; **only** `logged()` is public API. App
  modules do not see any extra public surface besides the extension.
- Generated source is formatted (KotlinPoet) and — contractually — never
  hand-edited; regenerate instead.
- The wrapper class exists per *interface* (per-file grouping), not per
  implementation class: implementations are attached through the extension.
