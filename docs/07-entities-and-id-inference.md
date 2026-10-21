# 07. Entities & id inference

ILogger proves entity and entity id **from the function signature** — at
compile time; an ambiguous signature is an error with a "Fix:" hint, not a
silently-wrong event.

## What counts as a "domain" parameter

The processor selects a *domain type* among parameters. A domain type either:

1. has an `id`-named property, or
2. is marked `@LogEntityId` on its identity property, or
3. is recognized as a Room entity (has `@PrimaryKey` — FQN detection, no Room
   dependency), or
4. is *loggable* unambiguously through the value grammar.

## Entity inference

| Signature | Entity | Why |
|---|---|---|
| `suspend fun insert(task: Task): Long` | `Task` | the domain parameter |
| `suspend fun complete(id: Long)` (Room `@Query UPDATE`) | — | no domain param → set `entity = Task::class` explicitly |
| `suspend fun byId(id: Long): TaskRow` | `TaskRow` | the **result type** is a domain candidate |
| `@Insert suspend fun insertAll(vararg tasks: Task): List<Long>` | `Task` (batch) | whole param via extractor only for values |

Batch/`List` results and vararg params: the processor can prove there is *no
single* entity id under the batch insert — the event carries the entity name
and a *summarized* extractor instead
([10-extractors.md](10-extractors.md)).

## Entity-id inference (highest priority first)

```
1. explicit @Log(entityId = "...")        → your expression wins over all
2. insert-shaped: id-typed insert/update/insert|upsert result → the result value
   (batch List<Long> results carry no single id — entityId omitted, no error)
3. id property of a single domain parameter ("id", @LogEntityId or @PrimaryKey)
4. single id-typed parameter (Long/Int/Short/String) — or, with several, the
   unique one named exactly "id"/ending in "id"
```

Anything else is ambiguous → compile error; the processor never guesses.

Examples that inferred **3** (single id param):

```kotlin
@Log(action = TaskActions.CLOSED, category = "workflows")
suspend fun close(taskId: Long)        // entity set explicitly; id=inference(3)

@Log(action = TaskActions.FETCHED, entity = Task::class)
suspend fun byId(id: Long): Task?
```

And 2 — Room insert row id (result is the id, not the constructor value 0):

```
[AUDIT] task.created actor=u-42(Grace) TaskRow(1) via TaskDao.insert {title=audit everything}
```

## Ambiguity → compile error

Two id-shaped parameters with no explicit selection is ambiguous:

```kotlin
@Log(action = "task.moved", entity = Task::class)
suspend fun move(fromId: Long, toId: Long)   // ← Which one is the identity?

// Diagnostic (verbatim shape):
//   Cannot infer entity ID of 'move'. No provable source: the result is not
//   an id, no entity id property (named 'id', or marked @LogEntityId / Room's
//   @PrimaryKey), and no single id-typed parameter.
//   Fix: specify @Log(entityId = "...") explicitly.
```

Explicit always wins: `@Log(entityId = "fromId")` or `"toId"` — the audit
record reflects your choice, deterministically.

## Ways to identify the id property

| Option | Example | Simplicity | Recommended when |
|---|---|---|---|
| Property named `id` | `val id: Long` | Zero — convention works | **🏆 Default**: normalize on `id` typing (KSP + Room `@PrimaryKey` both honor it) |
| `@LogEntityId` on the property | `@LogEntityId val key: String` | One annotation | The stable id is *not* named `id` in your domain |
| Room `@PrimaryKey` (auto) | `@PrimaryKey val uid: Long` | Zero for Room entities | Room entities — honored automatically |
| Explicit `@Log(entityId = …)` | `@Log(entityId = "task.key")` | One attribute per site | Exotic signatures; never for the common case |

**Recommendation:** rely on inference (`id` / `@PrimaryKey`); mark
`@LogEntityId` on entities whose identity property has a different name; use
explicit `entityId` expressions only where inference is legitimately
ambiguous.
