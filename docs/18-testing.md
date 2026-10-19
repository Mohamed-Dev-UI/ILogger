# 18. Testing with TestKit (`ilogger-testing`)

The testing module provides a deterministic kit that removes the usual moving
parts (async workers, clocks, UUIDs):

```kotlin
val kit = TestKit.create()      // INLINE dispatch + FixedClock + sequential ids
val dao = InMemoryTaskRepository().logged(kit.logger)

runBlocking { dao.insert(Task(title = "x")) }

kit.sink.awaitCount(1)          // real-time await for async-producing tests
val event = kit.sink.events.single()
assertEquals("task.created", event.action)
assertEquals("e1", event.id)            // SequentialIdGenerator
assertEquals("Task", event.entity)
```

## Components

| Piece | Provides |
|---|---|
| `TestLogSink` | captures events; `awaitCount(n)` polls real time because async workers do not follow virtual time; `events` list for inspection |
| `TestKit` | pre-wired logger (INLINE, `FixedClock`, sequential ids) |
| `SequentialIdGenerator` | `e1, e2, ...` — unique per kit instance |
| `FixedClock` | injectable clock for timestamp assertions |

## Determinism notes

- With `DispatchMode.INLINE` dispatch, `record` events appear immediately; no
  awaits needed for the suspend path (ksp test: 15 passing behaviors rely on
  this).
- For `recordNow` paths (non-suspend functions default to ASYNC), use
  `awaitCount(...)`: the worker thread delivers asynchronously relative to
  the calling coroutine.
- JUnit boxing gotcha: `assertEquals(42L, event.someLong)` — mind the `L`.

## What we test this way (ksp-tests module)

`CodegenBehaviourTest` — 15 verified behaviors:

1. entity inference (param domain, Room insert result row-id)
2. single id-typed parameter inference for non-insert operations
3. batch inserts: provable-no entity id, entity name kept
4. changes recorded on SUCCESS
5. constants / context / actor-derived metadata keys
6. extractor metadata (count/summary with `name`)
7. enum metadata projects as `.name`
8. ambient actor per operation and `actor = "userId"` explicit override
9. non-suspend → `recordNow` async semantics (`runBlocking`)
10. `dispatch = INLINE` + `schemaVersion = 2` events
11. enabled=false fast path (`enabled` volatiles)
12. functions without `@Log` delegate untouched (passthrough)
13. failure events rethrow verbatim (exception identity preserved)
14. START events for `before = true` abstractions
15. supplied/sentinel (`Nothing::class` → `Void`) defaults handled

## Assertion recipe

Prefer asserting on **structured values** (`event.schemaId` rather than
formatted strings). For sink-index positioning see `TestLogSink.awaitCount`.
