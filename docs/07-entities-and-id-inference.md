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
1. entityId property of a single domain parameter   → task.id
2. Room-shaped insert result                        → Long/id result (@Insert/@Upsert shaped)
3. single id-typed parameter                        → the Long/String/Int param
4. explicit @Log(entityId = "...")                  → your expression wins over all
```

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
//   Fix: set entity/zone or the id explicitly: @Log(entityId = "fromId")
```

Explicit always wins: `@Log(entityId = "fromId")` or `"toId"` — the audit
record reflects your choice, deterministically.
