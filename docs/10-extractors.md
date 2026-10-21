# 10. Extractors

Extractors are the application's escape hatch for *derived* log values: small
classes that convert a selected value into a log-safe primitive. The runtime
contract lives in `io.ilogger.core`:

```kotlin
public fun interface LogValueExtractor<in T> {
    /** Converts [value] into a loggable primitive, or null to omit. */
    public fun extract(value: T): Any?
}
```

Referenced from `LogMeta(extractor = SomeExtractor::class, ...)`. ILogger
never serializes whole domain objects by default — an extractor is the
explicit, application-chosen projection.

## Rules

1. **Public no-argument constructor required** — generated code instantiates
   each unique extractor **once per wrapper**
   (`private val iloggerExtractor0 = BatchSizeExtractor()`). Construction cost
   is paid once, not per event.
2. **Return loggable values only** — numbers, booleans, strings, enums (as
   `.name`), or `null` to omit the entry entirely.
3. **The only way to reference vararg / collection parameters** — the
   processor provably cannot pick "the" element of a collection
   (`tasks.first().title` is a compile error); an extractor summarizes
   instead: `LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")`.
4. **`name` is required** in most extractor uses: keying stays explicit and
   collision-free.
5. **No sensitive mixing**: `extractor` + `sensitive = MASK/HASH` is a compile
   error — the extractor's output shape is application-defined, so the policy
   must be applied inside the extractor itself.
6. **Keep them cheap, pure, and null-safe.** They run on the caller's path.

## Living examples (from the KSP test fixture)

```kotlin
/** Explicit extractor: converts a whole Task to a log-safe value. */
class TaskSummaryExtractor : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = value.title
}

/** Vararg parameters can only be converted as a whole (see @Log grammar). */
class BatchSizeExtractor : LogValueExtractor<Array<out Task>> {
    override fun extract(value: Array<out Task>): Any? = value.size
}

@Log(action = TaskActions.BATCH_CREATED,
     metadata = [LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")])
suspend fun insertAll(vararg tasks: Task): List<Long>

@Log(action = TaskActions.SUMMARIZED,
     metadata = [LogMeta("task", extractor = TaskSummaryExtractor::class, name = "summary")])
suspend fun summarize(task: Task): Int
```

Events: `{count=4}` — the *count*, not the items; `{summary="ship it"}` — a
chosen projection, never the object.

## The throwing-extractor edge (know it, design around it)

Generated code calls `extract(...)` while building the metadata map:

| Wrapper shape | What happens when an extractor throws |
|---|---|
| `onFailure = true` (try/catch present) | The throw is caught by the wrapper's catch → recorded as a FAILURE event, then **rethrown to the caller** |
| `onFailure = false` (default body, no try/catch) | The throw propagates to the caller; **no event is recorded at all** |

**Recommended:** extractors never throw.Catch defensively inside `extract`
and return `null` (omit) or a safe fallback value; an extractor is a
projection function, and a missing optional field beats a broken audit path.

## When to use vs plain expressions

| Option | Best for | Cost |
|---|---|---|
| Plain property chain `LogMeta("task.title")` | Direct values — the default | Static validation, one property read |
| Extractor | Batch summaries, derived metrics, multi-field projections, whole-object reduction | One extra class; one call per event; not statically shape-checked |
| Constant `'literal'` | Labels that never vary per call | Zero |

**Recommended hierarchy:** property chain → constant → extractor. Reach for
an extractor only when the compiler cannot express the value you want.
