# 25. Performance & benchmark

The `:benchmark` module ships two harnesses:

- **JMH suite** (`EnginePathsBenchmarks`) — the primary, CI-grade
  methodology (forked JVM, warmups, statistical error margins). Run with
  `.\gradlew.bat :benchmark:mainBenchmark`.
- **Deterministic harness** (`Main.kt`) — quick validation mode that pins
  the relative ordering of the engine paths in a few seconds. Run with
  `.\gradlew.bat :benchmark:run`. The harness counts loop overhead into its
  per-op numbers, so its absolute values read higher than JMH's; use it for
  comparisons, JMH for budgets.

## JMH results (Windows, JDK 24, 5×1s warmup + 7×1s measurement, 1 fork)

| Case | avgt |
|---|---:|
| 1. disabled logger (volatile check only) | **1.4 ns/op** |
| 2. filtered diagnostic (`minLevel = WARN`) | **1.8 ns/op** |
| 3. `info()` inline + direct nop sink | 273 ns/op |
| 4. `record()` audit inline, 4 metadata (incl. `runBlocking` bridge) | 1,739 ns/op |
| 5. `recordNow()` async enqueue, 4 metadata | 999 ns/op |
| 6. `recordNow()` buffered enqueue, 4 metadata | 756 ns/op |
| 7. `recordNow()` fanned out to two per-sink pipelines | 5,168 ns/op |
| 8. generated `@Log` wrapper dispatch | 1,268 ns/op |
| 9. generated wrapper + metadata extraction | 1,723 ns/op |
| 10. INLINE audit into a rotating file sink | 11,797 ns/op |

Read of the numbers:

- **Cases 1–2** are the application-side kill switches — one volatile read or
  one ordinal comparison, with no allocation on the rejected path.
- **Case 3** uses a `DirectLogSink`, so inline diagnostics bypass the
  coroutine bridge entirely. Without a direct sink the engine must spin a
  bounded `runBlocking` write per event — use `DirectLogSink` in hot inline
  configurations.
- **Case 4** adds event construction, metadata maps and the suspend →
  blocking bridge. The bridge is a constant the audit path itself doesn't add
  inside generated wrappers (they are already `suspend`).
- **Cases 5–6** are producer admission only; the write happens off-thread.
  The async path is slightly costlier than buffered because it wakes a worker
  per event. The default event-id generator now uses `ThreadLocalRandom`
  (RFC 4122 v4) instead of shared `SecureRandom`: that change alone moved
  case 5 from **4,173 ns to 999 ns** on this host.
- **Case 7** adds one offer per matched binding. On the shared host two
  workers keep both queues saturated and compete for CPU, so the producer-side
  number grows faster than 2×; the isolation guarantee (one slow sink cannot
  delay another) is unchanged.
- **Case 8/9** run the real KSP-generated wrapper from `BenchRepository.kt`;
  metadata extraction adds the compile-time-selected map entries.
- **Case 10** is the full synchronous file path (encode + buffered write) on
  the producer thread via `recordBlocking`; suspend `TextFileSink` writes hop
  to a per-instance `Dispatchers.IO.limitedParallelism(1)` view, so async
  dispatch keeps file IO off the producer and ordered per sink.

## Deterministic harness results (`n = 100_000 × 7` iterations, 3 warmups)

The harness validates six cases without JMH (its loop and `runLoop` suspend
machinery show up in the absolute numbers):

| Case | Median |
|---|---:|
| 1. disabled logger | 64 ns |
| 2. `info()` inline + blocking nop sink | 9,115 ns |
| 3. `record()` audit inline, 4 metadata | 1,903 ns |
| 4. `recordNow()` async enqueue only | 1,381 ns |
| 5. async file sink, enqueue + drain (rotating, no fsync) | 1,531 ns |
| 6. `record()` audit inline + hash chain (G-36) | 7,901 ns |

Case 5 shows the file sink at ~653k events/s while the producer only enqueues:
the sink's IO dispatcher overlaps encoding and buffered writes with the next
batch, and the final `flush()` drains the pipeline. Case 2 uses a non-direct
sink on purpose, so it includes the engine's bounded `runBlocking` bridge and
reads far above JMH case 3. The harness predates the direct-sink diagnostic
path and the fast id generator, so cases 2–4 remain pessimistic; the JMH suite
(and the gate) are authoritative. Ordering within the harness is still
identical to the JMH suite for the audit cases.

Case 6 wraps the case-3 shape in `HashChainSink` and pays the G-36 stamping
cost on the sink side: **~6 µs/event** over case 3 (same host, ~1.8 µs
canonical chain input + ~3 µs HMAC-SHA256 + stamp metadata copy, measured
per component). Under async dispatch the producer thread does not pay it —
the drain worker does. Verification is O(n) per file.

## Budget gate

`BudgetGateTest` enforces producer-side ceilings on the dedicated Linux CI
job. It runs only with `ILOGGER_BENCH_GATE=true`, takes the median of nine
100k-event runs, subtracts the empty-loop baseline from the disabled path,
and allows 15% overshoot for runner noise:

| Path | Budget | Measured (Windows, informational) |
|---|---:|---:|
| disabled logger | 2 ns/op | 0.0 ns/op net of loop baseline |
| async enqueue | 1,000 ns/op | 952 ns/op |
| buffered audit enqueue | 3,000 ns/op | 937 ns/op |

Budgets live in `benchmark/src/main/kotlin/benchmark/Budgets.kt`; change one
only together with a documented reason. Windows developer runs are
informational — CI on Linux is the enforcement point.

## The overhead model

A wrapped function costs:

```
if (!logger.enabled)                // volatile read → disabled path ≈ 1–2 ns
    return delegate.f(args)
try {
    val result = delegate.f(args)
    logger.record(...)              // the audit path (async by default)
    return result
} catch (e: Throwable) { ...rethrow }
```

Nothing in the generated wrapper allocates beyond the event itself; delegation
goes through Kotlin's interface delegation (the exact behavior of a manual
adapter class minus the maintenance).

## Reproduce

```bash
# JMH suite (primary numbers)
.\gradlew.bat :benchmark:mainBenchmark

# quick validation profile (1 warmup × 2×1s iterations)
.\gradlew.bat :benchmark:mainQuickBenchmark

# deterministic harness
.\gradlew.bat :benchmark:run

# budget gate (what CI enforces)
ILOGGER_BENCH_GATE=true ./gradlew :benchmark:test --tests benchmark.BudgetGateTest
```

JMH tuning lives on `EnginePathsBenchmarks` (`@Warmup`/`@Measurement`/`@Fork`);
the *quick* profile's parameters are in `benchmark/build.gradle.kts`. Harness
`n`/`iterations` are in `benchmark/src/main/kotlin/benchmark/Main.kt`. Budgets
and tolerance are in `benchmark/src/main/kotlin/benchmark/Budgets.kt`.

## Guidance

- Production: `BUFFERED` (the engine default) dispatch producers stay
  sub-microsecond-ish enqueue-only while audit IO happens off-thread in real
  batches.
- Tests and demo apps: `INLINE` keeps ordering deterministic.
- Audit rates that reach 100k events/s: use `BUFFERED` batching with tuned
  `batchSize`/`flushIntervalMillis` so sinks see I/O-sized writes.
- Disabled-first (`enabled = false`) removes essentially all cost —
  feature-flag whole flavors without code changes (or with the processor kill
  switch `ilogger.enabled=false` for compile-time removal).
- Under sustained load, shed diagnostics *before* the engine does:
  `isBacklogged()` / `availableCapacity()` plus `tryRecord`, which never
  blocks or throws regardless of the configured overflow policy.

### Choosing the dispatch path

| Need | Path | Producer cost | Verdict |
|---|---|---|---|
| Fastest possible filtering (per-call) | level-gated diagnostics or `enabled=false` | ~ns | For hot loops that must check |
| Low producer cost, async delivery | `recordNow()` async enqueue | ~1 µs case 5 | Non-suspend producers |
| Strong audit guarantees on suspend ops | `record()` via generated wrapper | ~1.3–1.7 µs incl. build + enqueue | **🏆 Standard path** |
| Durability before return | `dispatch = INLINE` per event | Case 10-like + sink write inline | Reserve for money/state-critical ops |

Numbers are environment-specific — treat the *ordering* (case 1 ≪ 4 < 2 ≈ 3)
as the durable result, and re-run the harness on the deployment target before
finalizing budgets.
