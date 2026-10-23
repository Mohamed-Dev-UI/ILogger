# 27. Instrumentation approaches

ILogger can capture events several ways. This doc lists **every** approach,
marks the recommended one, and compares them honestly — pick per call site,
not per project.

## The four instruments

| Instrument | Where it lives | What it is |
|---|---|---|
| `@Log` + generated wrapper | `ilogger-ksp` | Compile-time instrumentation of an interface |
| `logger.record(...)` (suspend) | `ilogger-core` | Manual, structured event, awaits dispatch |
| `logger.recordNow(...)` | `ilogger-core` | Manual, structured event, non-suspending |
| `logger.trace..error(...)` | `ilogger-core` | Diagnostic messages (not audit events) |

## Approach A — `@Log` + generated wrapper (recommended)

```kotlin
object TaskActions {
    const val CREATED = "task.created"
}

interface TaskRepository {
    @Log(action = TaskActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: Task): Long
}

val repo = InMemoryTaskRepository().logged(logger)   // one line at wiring time
```

The processor proves entity/id/actor from the signature, validates ambiguity
at compile time, and the wrapper emits success after the call succeeds plus
opt-in failure/start events — always rethrowing the original exception.

**🏆 Recommended** for *domain operations* (repositories, DAOs, use cases):
the instrumentation is written once at the contract, not repeated at every
call site, and cannot drift from the code it audits.

### Approach B — manual `record(...)` at boundaries

```kotlin
suspend fun checkout(order: Order, cart: Cart): Receipt {
    return cart.checkout(order).also { receipt ->
        logger.record(
            action = OrderActions.CHECKED_OUT,
            entity = "Order",
            entityId = receipt.orderId,
            metadata = mapOf(
                "itemCount" to cart.size,
                "total" to receipt.totalCents,
            ),
        )
    }
}
```

No annotation, no processor: the audit line lives where the operation does.
Everything is optional at compile time — which is also the weakness: nothing
forces you to record entity/actor, and a refactor can silently drop the line.

#### Draft-based recording

When a call site reads better as a value than as fourteen parameters (or when
you build the event description in one place and record it in another), use
`LogEventDraft`. Only `action` is required:

```kotlin
val draft = LogEventDraft(
    action = OrderActions.CHECKED_OUT,
    entity = "Order",
    entityId = receipt.orderId,
    metadata = mapOf("itemCount" to cart.size),
)

logger.record(draft)              // suspend; honors ambient coroutine context
logger.recordNow(draft)           // synchronous contexts, thread-local context
logger.recordBlocking(draft)      // explicit admission result + timeout
```

`dispatch` and `sampling` on the draft are recording hints and are not stored
on the resulting event. The logger still mints the event id and timestamp and
resolves correlation/causation ids from the ambient context.

`LogEvent` defensively copies `metadata` and `changes` into unmodifiable
containers at construction, so mutating your own map or list after recording
can never rewrite an event that is already queued or persisted.

### Approach C — `recordNow(...)` for non-suspend code

```kotlin
// inside a synchronous callback, a plain (non-suspend) DAO, or a listener:
fun onDelete(id: Long) {
    store.delete(id)
    logger.recordNow(
        action = "task.deleted",
        entity = "Task",
        entityId = id.toString(),
        source = "TaskStore.onDelete",
    )
}
```

Reads ambient context from the **thread-local bridge only** (`LogContexts.onCurrentThread`):
coroutine-scoped `withActor { }` is invisible here by design. `INLINE`
dispatch is rejected (`IllegalStateException`) — use the suspend `record` for
synchronous-delivery semantics.

### Approach D — diagnostic methods `trace..error`

```kotlin
logger.warn("cart.checkout slow", "tookMillis" to 412, "itemCount" to 6)
```

Cheapest call in the library (min-level + enabled check, then one event).
Emits a diagnostic event with action `"log.warn"` (and a `"message"` metadata
entry), never `AUDIT` guarantees — no entity/actor inference, only the
thread-held ambient actor. Use for *what the app is doing*, not *who did what*.

## Flow-returning functions

Codegen rejects `@Log` on Flow-returning functions (a stream has no single
success/failure boundary). Log at the collection site:

```kotlin
@Log(FolderActions.WATCHED, metadata = [LogMeta("folder.path")])   // event: subscription started
fun watch(folder: Folder): Flow<Change> = store.flow(folder)

// at the collection site — per-change events:
watch(f).collect { change ->
    logger.record(action = FolderActions.CHANGED, entity = "Folder",
                  entityId = folder.path, metadata = mapOf("kind" to change.kind.name))
}
```

Two instruments cooperating: one coarse `@Log` event for the subscription,
manual `record` for the individual outcomes. (Full rules in
[04-log-annotation-reference.md](04-log-annotation-reference.md) and
[24-limits-and-constraints.md](24-limits-and-constraints.md).)

## Comparison

| Criterion | A — `@Log` + wrapper | B — `record` | C — `recordNow` | D — `trace..error` |
|---|---|---|---|---|
| Audit correctness (actor/entity/outcome/failure) | Enforced from signature | Your responsibility | Your responsibility | Not applicable |
| Compile-time validation | Yes (ambiguity is an error) | None | None | None |
| Call-site noise | One line at wiring; zero per call site | 3–8 lines per operation | 3–8 lines per operation | One line |
| Context (actor/correlation) | Coroutine scope → automatic | Coroutine scope → automatic | Thread-local bridge only | Thread-local only |
| Runtime overhead | One delegated interface call + event build | Event build | Event build (no suspend machinery) | Lowest (skippable before build) |
| Failure semantics | Exception rethrown untouched, failure event recorded | You write try/catch (or lose failure events) | You write try/catch | — |
| Development speed | Slower first time (annotation + wrapper), then fastest per feature | Fast first time, slowest to maintain | Fast first time | Fastest |
| Maintenance cost | Lowest — contract-owned, codegen can't drift | Highest — every call site is bespoke | High | Low |
| Works for Flow / streams | No (rejected) | Yes | Yes | Yes |
| Works for non-suspend code | Yes (`recordNow` under the hood) | No (suspend only) | Yes | Yes |
| Recommended when | Domain operations (default) | Boundaries codegen can't reach | Synchronous callbacks/DAOs | Diagnostics only |

## Recommended strategy: hybrid, codegen-first

1. **Annotate domain operations** with `@Log` on interfaces. This is the
   backbone: 90 % of audit events with ~0 call-site code.
2. **Diagnostics everywhere** via `trace..error` — cheap, filtered, never
   confused with audits.
3. **Manual `record`** only where codegen cannot reach: Flow collection sites,
   library-internal boundaries you don't want to annotate, dynamically
   dispatched operations.
4. **`recordNow`** only inside non-suspend contexts that genuinely need a
   structured event (sync DAOs, callbacks, instrumentation tests).
5. Never hand-maintain a duplicate of a generated event: if a call needs
   *different* metadata, adjust `@Log` instead of writing a second `record`.
   For wrapped-in-wrapped duplicates use `suppressSources` (see
   [11-actors-and-ambient-context.md](11-actors-and-ambient-context.md)).

Decision shortcut:

- Is it a **domain operation** on an interface? → Approach A.
- Is it **diagnostics**? → Approach D.
- Is it a **stream/Flow** or an unannotatable boundary? → Approach B.
- Is the code **non-suspend** but must record? → Approach C.
