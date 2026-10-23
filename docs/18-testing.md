# 18. Testing with TestKit (`ilogger-testing`)

The testing module provides a deterministic kit that removes the usual moving
parts (async workers, clocks, UUIDs):

```kotlin
val kit = TestKit.create()      // INLINE dispatch + FixedClock + sequential ids
val dao = InMemoryTaskRepository().logged(kit.logger)

runBlocking { dao.insert(Task(title = "x")) }

val event = kit.assertLogged(action = "task.created").single()
assertEquals("e1", event.id)            // SequentialIdGenerator
assertEquals("Task", event.entity)
kit.assertNoSensitive()                  // no plaintext passwords/tokens/emails
```

## Assertion DSL

| Helper | Purpose |
|---|---|
| `assertLogged(action, level, times, predicate)` | At least one match by default; `times = n` requires exactly `n`. Returns the matches and lists captured actions on failure. |
| `assertNotLogged(action, level, predicate)` | Fails if any captured event matches; the failure prints the event's golden snapshot. |
| `eventsFor(action)` | All captured events for one action, arrival order. |
| `awaitEvent(predicate) { ... }` | Suspends until a matching event arrives (real-time poll for async workers); throws `AssertionError` with captured actions on timeout. |
| `assertNoSensitive(vararg keys)` | Fails when a sensitive-looking metadata key (`password`, `token`, `secret`, `authorization`, `cookie`, `apiKey`, `email`, `ssn`, card number, ...) holds a plaintext value. Masked (`LogMasking.MASK`) and hashed (`LogMasking.hash`) values pass; nested maps are scanned; pass extra keys for your vocabulary. |
| `drain(timeoutMillis)` | Waits for every admitted event to reach sinks, then flushes them. Returns `false` on timeout. Uses the G-02 barrier — no sleeps. |
| `golden(event)` | Canonical text snapshot (schema, level, outcome, ids, routing, sorted metadata, changes, failure) for `assertEquals` snapshot tests. |
| `advanceTimeBy(millis)` | Moves the injected `FixedClock` forward deterministically. |

## Components

| Piece | Provides |
|---|---|
| `InMemoryLogSink` | published in-memory sink primitive: thread-safe capture — `events()` immutable snapshot, `last()`, `count()`, `clear()`, `awaitCount(n, timeoutMillis = 5_000)`; extend it for destination-specific fakes |
| `TestLogSink` | the `InMemoryLogSink` subtype wired by `TestKit` |
| `TestKit` | pre-wired logger (INLINE, `FixedClock` at epoch `1_700_000_000_000`, sequential ids) plus the assertion DSL above |
| `SequentialIdGenerator` | `e1, e2, ...` — unique, atomic |
| `FixedClock` | injectable clock; `clock.set(millis)` or `kit.advanceTimeBy(millis)` moves deterministic time |

`TestKit.create(configure)` runs **your** `configure` block last, so presets
can be overridden (e.g. `dispatch = DispatchMode.BUFFERED` when testing batch
behavior, then `kit.drain()`).

## Fakes, chaos and the sink conformance suite

Every published fixture is a real `LogSink`/`DeadLetterSink` you can pass to
`ILogger.create`; none needs a mocking framework.

| Fixture | Use it for |
|---|---|
| `InMemoryLogSink` / `TestLogSink` | the default capture sink; `TestLogSink` is the TestKit-facing name |
| `FakeLogSink` | stand-in for a file, Room table or HTTP collector: records `events()`, `batches()` and codec-encoded `payloads()`, plus call counters. Inject transient failures (`failNextWrites(n)`), a deterministic flaky endpoint (`failEveryNthWrite(n)`), shutdown faults (`failFlushWith`, `failCloseWith`) and latency (`delayWrites(ms)`, virtual-time friendly) |
| `BlockingLogSink` | hold every write behind a one-shot gate: `awaitFirstWrite()`, `release()`. Backpressure, queue overflow and shutdown timing; `close()` releases the gate so a test cannot hang your engine shutdown |
| `ChaoticLogSink` | seeded chaos wrapper around any sink: deterministic write failures and latency (`failureRate`, `slowRate`, `seed`). Failed calls record nothing; `callCount()`/`failedCallCount()`/`delayedCallCount()` make accounting assertions exact |
| `RecordingDeadLetterSink` | records `DroppedEvent`s and counts flush/close; pair it with a chaos sink to prove every loss is reported |

```kotlin
@Test
fun lossesAreAccountedFor() {
    val delivered = InMemoryLogSink()
    val chaos = ChaoticLogSink(delivered, seed = 42, failureRate = 0.35, slowRate = 0.15)
    val deadLetters = RecordingDeadLetterSink()
    val logger = ILogger.create {
        sink(chaos)
        deadLetter(deadLetters)
        dispatch = DispatchMode.INLINE
        retryMaxAttempts = 1
    }

    repeat(100) { logger.recordNow(action = "audit.$it", dispatch = DispatchMode.INLINE) }
    logger.closeAndReport()

    assertEquals(100, delivered.count() + deadLetters.count())
}
```

### Validating a new sink

Extend `LogSinkConformance` in the sink's module. The suite writes through the
public `LogSink` API and checks single and batch writes, ordering, empty
batches, flush/close idempotency, data retention across close, equal
timestamps, the `DirectLogSink` fast path, and concurrent writers without loss
or duplication:

```kotlin
class MySinkConformanceTest : LogSinkConformance() {
    override fun createSink(): LogSink = MySink(...)

    override fun deliveredIds(sink: LogSink): List<String> =
        (sink as MySink).readBack().map { it.id }
}
```

`createSink()` must return a fresh sink per test. `deliveredIds()` is called
after `flush()` and must read back everything written in this test. Optionally
override `deliveredEvents()` when the destination reads back losslessly; the
default returns empty and skips content-equality assertions (useful for a text
file with a human-readable codec).

The suite is already adopted for `InMemoryLogSink`, `FakeLogSink`,
`TextFileSink` and `RoomLogTarget`, so any regression in those sinks fails CI.

### Codec and parser property tests

`JsonEventCodecPropertyTest` and `RoomLogImportPropertyTest` run seeded,
deterministic property-style tests without a property-testing dependency.
They generate events with quotes, backslashes, newlines, control characters,
Unicode, nested containers and unknown metadata values, then assert that:

- encoding always yields exactly one line of parseable JSON;
- required fields and string metadata round-trip exactly;
- encoding is deterministic and never throws on unexpected value types;
- real Room export/import preserves payloads and every flat column;
- arbitrary non-object JSONL lines fail with the correct line number.

Both run as part of `:ilogger-serialization:build` / `:ilogger-room:build` in
CI. Use the same pattern for a custom codec or parser: a seeded
`kotlin.random.Random`, a generator for edge-case strings, and assertions on
the stable properties rather than one hand-written case.

## Virtual time

`TestKit.create(workerDispatcher)` accepts any coroutine dispatcher for the
engine's pipeline workers. Pass a coroutines-test dispatcher to drive async
delivery with virtual time instead of real sleeps:

```kotlin
@Test
fun asyncDeliveryIsDeterministic() = runTest {
    val kit = TestKit.create(UnconfinedTestDispatcher(testScheduler)) {
        dispatch = DispatchMode.ASYNC
        batchSize = 1
    }
    kit.logger.use { logger ->
        logger.recordNow(action = "audit.saved")
        kit.assertLogged(action = "audit.saved")   // delivered deterministically
    }
}
```

`UnconfinedTestDispatcher` delivers inline; `StandardTestDispatcher(plan)`
queues worker tasks so `plan.advanceUntilIdle()` controls exactly when they
run. Prefer `ASYNC` (serial pipeline) with a test dispatcher: the default
`BUFFERED` loop mixes `flushIntervalMillis` deadlines with the real clock.

## Determinism notes

- With `DispatchMode.INLINE` dispatch, `record` events appear immediately; no
  awaits needed for the suspend path (the ksp codegen suite relies on this).
- For `recordNow` paths (non-suspend functions default to ASYNC), use
  `kit.drain()` before asserting, or `awaitEvent { ... }` for the specific
  event you need.
- `awaitCount` and `awaitEvent` deliberately poll the **real** clock even
  under `runTest`'s virtual scheduler — async workers on real dispatchers
  deliver in real time. Use a test `workerDispatcher` when you want virtual
  time end to end.
- `recordBlocking(...)` defaults to `dispatch = ASYNC`, which waits for
  *admission*, not delivery. Pass `dispatch = DispatchMode.INLINE` when the
  next line asserts on the sink.
- JUnit boxing gotcha: `assertEquals(42L, event.someLong)` — mind the `L`.
- For async engine tests, `logger.awaitIdle(timeoutMillis)` waits for every
  event admitted before the call without flushing sinks;
  `logger.flush(timeoutMillis)` additionally flushes sinks and returns a
  `FlushReport` (`timedOut`, counters, `droppedByReason`).
- `kit.golden(event)` is stable only while the fixed clock, sequential ids
  and event contents are stable; that is exactly what `TestKit` presets.

## TestKit vs. rolling your own

| Criterion | `TestKit.create()` | Hand-built fake (own `ILogger.create { ... }` per test) |
|---|---|---|
| Setup per test | One line | 5–10 lines, repeated or shared fixture |
| Deterministic clock/ids | Pre-wired, overridable | Manual wiring each time |
| Async-era flakiness | `awaitCount` handles it | Usually rediscovered per project |
| Error modes / dispatch experiments | `configure { ... }` override | Rebuild from scratch |
| Verdict | **🏆 Recommended default** | Acceptable when a suite needs exotic engine setups shared across 10+ tests |

## What we test this way (ksp-tests module)

`CodegenBehaviourTest` verifies generated-wrapper behavior:

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
