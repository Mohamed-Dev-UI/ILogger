# 04. `@Log` annotation reference

`@Log` applies to **interface functions** (implementation classes are wrapped
by the processor; see [22-generated-code-guide.md](22-generated-code-guide.md)).

Complete signature (defaults shown):

```kotlin
@Log(
    action: String,              // required
    schemaVersion: Int = 1,
    entity: KClass<*> = Nothing::class,
    entityId: String = "",       // source expression
    actor: String = "",          // source expression
    level: LogLevel = LogLevel.AUDIT,
    category: String = "",       // "" = default route
    dispatch: DispatchMode = DispatchMode.DEFAULT,
    metadata: Array<LogMeta> = [],
    changes: Array<LogChange> = [],
    before: Boolean = false,     // also emit a START event
    onSuccess: Boolean = true,
    onFailure: Boolean = false,  // emit failure event on throw
    sampling: Double = -1.0,     // per-event sampling override
    description: String = "",
)
```

## Parameter notes

- **`action`** — canonical action code; convention `<domain>.<event>` in
  lower snake_case segments. Always via a `const val` constants object.
- **`schemaVersion`** — payload version; the event's `schemaId` becomes
  `action@v2` when set to 2 ([06](06-actions-and-schema-versioning.md)).
- **`entity`** — explicit entity type; otherwise inferred
  ([07](07-entities-and-id-inference.md)). `Nothing::class` is the "unset"
  sentinel (KSP quirk: defaults surface as `java.lang.Void`).
- **`entityId`** — explicit source expression; see the grammar
  ([08](08-value-expressions.md)). When unset, inference runs.
- **`actor`** — explicit actor source overriding the ambient actor for this
  event, e.g. `actor = "userId"` (parameter access).
- **`level`** — default `AUDIT`; non-audit levels are for diagnostic-shaped
  side-events and *are* sampled. `sampling < 1.0` on AUDIT is a compile error.
- **`category`** — sink routing key ([13](13-categories-and-routing.md)).
- **`dispatch`** — `INLINE`, `ASYNC`, `BUFFERED` or `DEFAULT` (follows the
  application configuration). `INLINE` + processor-generated `recordNow`
  (non-suspend function) is a compile error.
- **`metadata` / `changes`** — explicit value selections; nothing else is
  captured.
- **`before`** — also records a START event before invoking the delegate:
  useful for may-crash operations. The START event cannot carry result-derived
  values (there is no result yet).
- **`onFailure`** — also records a FAILURE event on the thrown exception.
  **Failure events never alter the exception**: it is rethrown verbatim.
- **`sampling`** — `-1.0` means "not set" (inherit engine configuration);
  anything else must be a rate in `[0.0, 1.0]` — other values are compile
  errors. `sampling < 1.0` on an AUDIT event is a compile error: audit
  records must never be silently dropped
  ([17](17-sampling.md)).
- **`description`** — free-form documentation embedded in generated code.

## Helper annotations

```kotlin
LogMeta("task.title")                       // selects an existing path
LogMeta("title", name = "myKey")            // renames the metadata key
LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")  // custom extractor
LogMeta("task.passwordHash", sensitive = SensitiveHandling.HASH)         // explicit policy

LogChange(field = "title", from = "task.title", to = "newTitle")  // before→after pair

@LogDao                             // interface-level: opt into the generated
@Dao interface TaskDao { ... }      // <Database>.logged(logger) facade
@LogDao(enabled = false)            // same DAO, facade returns it raw
@LogDao(hilt = true)                // also generate a Hilt @Module binding
                                    // the wrapped DAO for injection
@LogDao(koin = true)                // same, for a generated Koin module
```

`@LogDao` changes only how the `.logged(logger)` adapter is obtained; methods
still need `@Log`. `hilt = true` additionally generates a module that provides
the wrapped DAO from the Hilt graph. See
[19-room-integration.md](19-room-integration.md).

Check the quick names: `LogChange.from` / `to` are source expressions
([08-value-expressions.md](08-value-expressions.md)); `name` renames the
metadata key; `sensitive` records the *chosen policy* for a sensitive value.

## Choosing the level

| Level | Use for | Audited by default? | Sampled by default? |
|---|---|---|---|
| `AUDIT` | Operations that must be accountable (who did what to which entity) | Yes — never min-level filtered | Never (compile-time enforced per event) |
| `INFO` / `WARN` / `ERROR` | Diagnostic-shaped side-events that still deserve structure | No — follows `minLevel` | Yes, engine-configurable |
| `DEBUG` / `TRACE` | High-volume diagnostics | No | Yes — typically sampled hard |

**Recommended:** leave `@Log` events at the default `AUDIT` unless the event
is truly diagnostic (a side-effect log of an *attempt*, not an accountable
action). If you find yourself using `INFO` for an operation that changes
state, it is an audit event.

## Choosing the dispatch mode

| Option | Meaning | Recommended when |
|---|---|---|
| `DEFAULT` (default) | Follows the engine configuration | **Almost always** — one dispatch decision for the whole app |
| `INLINE` | Synchronous write, ordering + failure visible to caller | Events that must be durable *before* the operation returns (e.g. money movement); suspend functions only |
| `ASYNC` | One-off async write to the worker | Single-event latency-sensitive paths |
| `BUFFERED` | Async + real batches to sinks | High-volume paths feeding database/file sinks |

Per-event `dispatch` overrides are the exception; set the engine default once
([16-dispatch-backpressure-error-modes.md](16-dispatch-backpressure-error-modes.md),
[26-configuration-reference.md](26-configuration-reference.md)).

## Minimal working shapes (all verified by the test suite)

```kotlin
@Log(action = TaskActions.CREATED)
suspend fun insert(task: Task): Long                       // entity+id from sig

@Log(action = TaskActions.UPDATED, changes = [LogChange(field = "title", from = "task.title", to = "newTitle")])
suspend fun update(task: Task, newTitle: String): Boolean

@Log(action = TaskActions.DELETED, before = true)          // START + SUCCESS events
suspend fun delete(task: Task)

@Log(action = TaskActions.FAILED, onFailure = true)        // FAILURE events too
suspend fun purge(task: Task)

@Log(action = TaskActions.FOUND_ID)
fun findById(taskId: Long): Task                           // non-suspend → recordNow
```
