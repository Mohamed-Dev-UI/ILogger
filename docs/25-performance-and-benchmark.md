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
| 2. `info()` inline + nop sink | 2,469 ns/op |
| 3. `record()` audit inline, 4 metadata (incl. `runBlocking` bridge cost) | 2,829 ns/op |
| 4. `recordNow()` async enqueue only | 2,655 ns/op |

Read of the numbers:

- **Case 1** is the application-side kill switch — one volatile read, 
  statistically the cheapest check in the framework.
- **Case 2/3** include event construction, metadata maps, level gates, the
  text codec *not* included (nop sink) — the upper bound of the synchronous
  audit path. Case 3 additionally pays `runBlocking` (suspend → blocking
  bridge); it is a constant the audit path itself doesn't add.
- **Case 4** is the producer cost of the buffered pipeline: enqueue only, the
  write happens off-thread.

## Deterministic harness results (`n = 100_000 × 7` iterations, 3 warmups)

The harness validates the same four cases without JMH (its loop and
`runLoop` suspend machinery show up in the absolute numbers):

| Case | Median |
|---|---:|
| 1. disabled logger | 23 ns |
| 2. `info()` inline + nop sink | 3,851 ns |
| 3. `record()` audit inline, 4 metadata | 5,386 ns |
| 4. `recordNow()` async enqueue only | 2,593 ns |

Ordering is identical to the JMH suite.

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
```

JMH tuning lives on `EnginePathsBenchmarks` (`@Warmup`/`@Measurement`/`@Fork`);
the *quick* profile's parameters are in `benchmark/build.gradle.kts`. Harness
`n`/`iterations` are in `benchmark/src/main/kotlin/benchmark/Main.kt`.

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

### Choosing the dispatch path

| Need | Path | Producer cost | Verdict |
|---|---|---|---|
| Fastest possible filtering (per-call) | level-gated diagnostics or `enabled=false` | ~ns | For hot loops that must check |
| Low producer cost, async delivery | `recordNow()` async enqueue | ~2.5 µs case 4 | Non-suspend producers |
| Strong audit guarantees on suspend ops | `record()` via generated wrapper | ~2.8 µs incl. build + enqueue | **🏆 Standard path** |
| Durability before return | `dispatch = INLINE` per event | Case 3-like + sink write inline | Reserve for money/state-critical ops |

Numbers are environment-specific — treat the *ordering* (case 1 ≪ 4 < 2 ≈ 3)
as the durable result, and re-run the harness on the deployment target before
finalizing budgets.
