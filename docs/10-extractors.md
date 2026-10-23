# 10. Extractors and value adapters

Extractors are the application's escape hatch for *derived* log values: small
classes that convert a selected value into a log-safe primitive. The runtime
contract lives in `io.ilogger.core`:

```kotlin
public fun interface LogValueExtractor<in T> {
    /** Converts [value] into a loggable primitive; null records no value. */
    public fun extract(value: T): Any?
}

public fun interface ValueAdapter<in T> {
    /** Converts [value] into a log-safe value; null records no value. */
    public fun render(value: T): Any?
}
```

Referenced from `LogMeta(extractor = SomeExtractor::class, ...)` or
`LogMeta(adapter = SomeAdapter::class, ...)`. ILogger never serializes whole
domain objects by default — a converter is the explicit, application-chosen
projection. The two differ only in intent: **extractors** reduce multi-field
objects (one call, full control of the shape); **adapters** render a single
value (one value in, one value out). They are mutually exclusive on one
`LogMeta` and, like extractors, adapters cannot be combined with
`sensitive = MASK/HASH` (apply policy inside `render` instead).

## Rules

1. **A constructor is required — or register the instance.** Generated code
   resolves each unique extractor through the logger's `ExtractorRegistry`
   (`logger.extractors.get(BatchSizeExtractor::class) { BatchSizeExtractor() }`).
   A class needs a public no-argument constructor (or all parameters
   defaulted); an extractor that needs app services declares
   `LogMeta(..., inject = true)` and is supplied at logger creation:
   `ILogger.create { extractor(MyExtractor::class) { MyExtractor(service) } }`.
   Each type resolves **once per logger**, so every wrapper and event shares
   one instance; see [Dependency injection and lifetime](#dependency-injection-and-lifetime).
2. **Return loggable values only** — strings, chars, numbers (including
   unsigned), booleans, enums, `UUID`/`Instant`/`Duration`,
   `BigDecimal`/`BigInteger`, or nested maps/lists/arrays of those (depth 4).
   Generated wrappers always call `validated()`: an unsafe output or a thrown
   exception degrades to `null`, and the metadata key remains with a `null`
   value. This validation is always on and independent of `StrictMode`.
3. **The only way to reference vararg / collection parameters** — the
   processor provably cannot pick "the" element of a collection
   (`tasks.first().title` is a compile error); an extractor summarizes
   instead: `LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")`.
4. **`name` is required** in most extractor uses: keying stays explicit and
   collision-free.
5. **No sensitive mixing**: `extractor` + `sensitive = MASK/HASH` is a compile
   error — the extractor's output shape is application-defined, so the policy
   must be applied inside the extractor itself.
6. **Keep them cheap, pure, and null-safe.** They run synchronously on the
   caller's path while the event is built.

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

## Dependency injection and lifetime

Extractors often need configuration, a renderer, or a repository. Declare the
dependency with `inject = true` and register a factory (or an instance) when
building the logger:

```kotlin
class RenderedTitleExtractor(private val renderer: TitleRenderer) : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = renderer.render(value)
}

interface ReportService {
    @Log(action = "report.created", entityId = "'fixed'",
          metadata = [LogMeta("task", extractor = RenderedTitleExtractor::class,
                              inject = true, name = "title")])
    suspend fun create(task: Task): Int
}

ILogger.create {
    extractor(RenderedTitleExtractor::class) { RenderedTitleExtractor(titleRenderer) }
    // or a ready instance:
    // extractor(RenderedTitleExtractor::class, RenderedTitleExtractor(titleRenderer))
    sink(sink)
}
```

Hilt:

```kotlin
@Provides @Singleton
fun provideLogger(titleRenderer: TitleRenderer): ILogger = ILogger.create {
    extractor(RenderedTitleExtractor::class) { RenderedTitleExtractor(titleRenderer) }
    sink(AndroidLogSink())
}
```

Koin:

```kotlin
single { RenderedTitleExtractor(get()) }
single { ILogger.create { extractor(RenderedTitleExtractor::class, get()) } }
```

Registry rules:

- **Resolution is lazy and cached per logger.** The factory runs on the first
  event that needs the extractor; concurrent first use creates exactly one
  instance. Factories run outside registry locks, so they may take their time
  (they still run on the caller's path).
- **Register before logging starts.** Registering a type that has already been
  resolved throws `IllegalStateException` — a late registration would silently
  produce different instances across wrappers.
- **`inject = true` is enforced.** Generated code calls
  `logger.extractors.require(...)`; an unregistered type throws at first use
  with the exact registration recipe in the message. A no-arg fallback that
  another use (without `inject`) may have created does **not** satisfy
  `require`.
- **One resolution per type per wrapper.** If the same extractor type appears
  both with and without `inject = true` in one interface/class, registration
  is required for all uses.
- **Suspend extractors are not supported.** `extract` runs synchronously while
  the metadata map is built; generated wrappers never start a coroutine for a
  projection. Precompute async data before calling the instrumented function,
  or pass a plain value and use `LogMeta("task.title")`.

## Value adapters: annotation-named and runtime-registered

`ValueAdapter` has two registration paths:

**1. Annotation-named** (`LogMeta(adapter = ...)`) — generated code
instantiates the class once per wrapper and calls `render` at the call site:

```kotlin
class ScoreAdapter : ValueAdapter<TaskScore> {
    override fun render(value: TaskScore): Any? = value.points  // unwrap the value class
}

@Log(action = "task.scored", metadata = [LogMeta("score", adapter = ScoreAdapter::class)])
suspend fun score(task: ValueTask, score: TaskScore): Int
```

Annotation-named adapters are **not** wrapped in `validated()`: `render` must
return a log-safe value (or `null`) on its own, and a throw follows the
wrapper's normal failure path (`onFailure` handling, otherwise it propagates
and no event is recorded). Return a logged value, not the domain object.

**2. Runtime registry** (`ILoggerBuilder.valueAdapter`) — applies to manual
`record(...)`/`tryRecord(...)` calls. Values whose **exact runtime class**
matches a registration are rendered at event materialization (metadata and
`FieldChange` before/after); everything else passes through untouched:

```kotlin
ILogger.create {
    valueAdapter(TaskScore::class) { it.points }
    sink(sink)
}
```

Registry rules: lookup is by exact class (no inheritance walk — register the
boxed types you emit); a throwing adapter degrades the value to `null`
(logging must never break the application); an empty registry is a
zero-cost pass-through. The JSON codec's canonical forms for the value model
are documented in [15-json-codec.md](15-json-codec.md).

## Extractor failures degrade, never break logging

Generated code always calls `extract(...)` through `validated()` while
building the metadata map:

| What the extractor does | What the event contains |
|---|---|
| Returns a log-safe value | That value under the metadata name |
| Returns `null` | `null` under the metadata name |
| Returns something not log-safe (domain object, non-string map key, too deep) | `null` under the metadata name |
| Throws any `Throwable` | `null` under the metadata name; the exception is swallowed |

The wrapper's own `onFailure` handling never observes an extractor throw, so a
broken projection cannot turn a successful operation into a FAILURE event nor
abort the caller. The trade-off is silence: a bad extractor shows up as a
`null` value, not as an error. Cover projections with unit tests.

**Recommended:** keep `extract` pure and cheap, and return `null` rather than
throwing for expected misses; validation then only has to guard against
programming mistakes.

## When to use vs plain expressions

| Option | Best for | Cost |
|---|---|---|
| Plain property chain `LogMeta("task.title")` | Direct values — the default | Static validation, one property read |
| Extractor | Batch summaries, derived metrics, multi-field projections, whole-object reduction | One extra class; resolved once per logger; one call + validation per event; output not statically shape-checked |
| Constant `'literal'` | Labels that never vary per call | Zero |

**Recommended hierarchy:** property chain → constant → extractor. Reach for
an extractor only when the compiler cannot express the value you want.
