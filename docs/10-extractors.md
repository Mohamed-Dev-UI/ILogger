# 10. Extractors

Extractors project richer values from a function's parameters/results into
event metadata — application-defined, side-effect free, deterministic.

## Living example (from the KSP test fixture)

```kotlin
class BatchSizeExtractor : MetadataExtractor {
    // whole vararg/list value; returns loggable scalars only
    override fun extract(value: Any?): Map<String, Any?> {
        val list = (value as List<*>).size
        return mapOf("size" to list)
    }
}

@Log(action = TaskActions.BATCH_CREATED,
     metadata = [LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")])
suspend fun insertAll(vararg tasks: Task): List<Long>
```

Event metadata: `{count=4}` — the *count*, not the items.

```kotlin
class TaskSummaryExtractor : MetadataExtractor {
    override fun extract(value: Any?): Map<String, Any?> {
        val task = value as Task
        return mapOf(
            "id" to task.id,
            "titleLength" to task.title.length,   // derived — projection, not passthrough
        )
    }
}

@Log(action = TaskActions.SUMMARIZED,
     metadata = [LogMeta("task", extractor = TaskSummaryExtractor::class, name = "summary")])
suspend fun summarize(task: Task): Int
```

## Rules

1. **Extractor + vararg/whole-value references**: the only way to reference
   vararg/list parameters — the processor provably cannot pick "the" element
   of a collection; an extractor summarizes instead.
2. **No sensitive mixing**: an extractor's output cannot be masked/hashed by
   the annotation layer; apply the policy inside the extractor itself.
3. **They run on the generated path per event** — keep them cheap, pure,
   null-safe (`null → empty map`); exceptions inside an extractor surface as
   a dispatch error (and *are* retried/dropped per `ErrorMode` like other
   event payloads).
4. **The `name`** parameter is required with extractors: metadata keying
   remains explicit and collision-free.

## When to use vs plain expressions

- Plain `LogMeta("task.title")` when a single property suffices — smaller,
  faster, statically validated.
- Extractors for batch summaries, derived metrics, multi-field projections,
  or access to values that need modest computation (length, count, slug).
