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
| `TestLogSink` | captures events — `events()` immutable snapshot, `last()`, `count()`, `clear()`; no filter helpers exist by design, filter `events()` yourself; `awaitCount(n, timeoutMillis = 5_000)` polls real time because async workers do not follow virtual time |
| `TestKit` | pre-wired logger (INLINE, `FixedClock` at epoch `1_700_000_000_000`, sequential ids) |
| `SequentialIdGenerator` | `e1, e2, ...` — unique, atomic |
| `FixedClock` | injectable clock; `clock.set(millis)` advances deterministic time |

`TestKit.create(configure)` runs **your** `configure` block last, so presets
can be overridden (e.g. `configure { dispatch = DispatchMode.BUFFERED }` when
testing batch behavior, then `sink.awaitCount(...)`).

## Determinism notes

- With `DispatchMode.INLINE` dispatch, `record` events appear immediately; no
  awaits needed for the suspend path (ksp test: 15 passing behaviors rely on
  this).
- For `recordNow` paths (non-suspend functions default to ASYNC), use
  `awaitCount(...)`: the worker thread delivers asynchronously relative to
  the calling coroutine.
- `awaitCount` deliberately sleeps on the **real** clock even under
  `runTest`'s virtual scheduler — async workers deliver in real time.
- JUnit boxing gotcha: `assertEquals(42L, event.someLong)` — mind the `L`.

## TestKit vs. rolling your own

| Criterion | `TestKit.create()` | Hand-built fake (own `ILogger.create { ... }` per test) |
|---|---|---|
| Setup per test | One line | 5–10 lines, repeated or shared fixture |
| Deterministic clock/ids | Pre-wired, overridable | Manual wiring each time |
| Async-era flakiness | `awaitCount` handles it | Usually rediscovered per project |
| Error modes / dispatch experiments | `configure { ... }` override | Rebuild from scratch |
| Verdict | **🏆 Recommended default** | Acceptable when a suite needs exotic engine setups shared across 10+ tests |

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
