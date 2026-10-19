# 06. Actions & schema versioning

## Action naming

Actions are stable public identifiers — treat them like API. Convention:

| Valid | Invalid |
|---|---|
| `task.created` | `createTask` |
| `task.batch.created` | `TASK_CREATED` |
| `http.request` | `task created` |

Segments: lowercase; `<domain>.<event>[.<variant>]`. Since they never change
silently, define them once:

```kotlin
object TaskActions {
    const val CREATED = "task.created"
    const val UPDATED = "task.updated"
    const val DELETED = "task.deleted"
}
```

`@Log(action = TaskActions.CREATED)` — `const val` reference required (KSP
reads the constant's *value*; a `val lateinit` style or non-const expression
is rejected).

## Schema versioning

Event payloads evolve. Every event carries `actionVersion` and a `schemaId`
derived from it:

```kotlin
@Log(action = TaskActions.DELETED, schemaVersion = 2)
suspend fun delete(task: Task)
```

- `schemaVersion = 1` (the default) keeps `schemaId = "task.deleted"` —
  no versioning suffix, so v1 remains the canonical form.
- `schemaVersion = 2` produces `schemaId = "task.deleted@v2"`.

Consumers route, transform and validate by `schemaId`. When the *meaning* of
metadata keys or changes changes, bump the version — old CSV/JSONL files
remain parseable forever with version-specific logic.

## Grep-ability guarantee

Because the action string is gatherable from the constants object, every event
is findable in a project just be referencing `TaskActions.CREATED` in your
IDE — no grep-through-cloud console needed.

## Audit events are never sampled

Audit-level events (the default for `@Log`) never drop by sampling; sampling
(a config, not annotation, concern) applies to diagnostic levels
([17-sampling.md](17-sampling.md)).

## Failure events have identity

The FAILURE event shares the action (and `source`) with the corresponding
success event, so completeness queries show both sides of operations:

```
task.created      : 1 001 events, 999 ok, 2 failure
http.request      : failure rows include structured payload
```
