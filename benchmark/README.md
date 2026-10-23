# `:benchmark` — overhead measurement and the budget gate

Three harnesses measure per-event costs of the engine paths:

## JMH suite (primary)

```bash
.\gradlew.bat :benchmark:mainBenchmark      # 5×1s warmup + 7×1s measurement
.\gradlew.bat :benchmark:mainQuickBenchmark # quick validation profile
```

`EnginePathsBenchmarks` (`src/main/kotlin/benchmark/JmhEngines.kt`) runs ten
paths under forked-JMH methodology via kotlinx-benchmark:

1. disabled logger (kill switch)
2. filtered diagnostic (`minLevel` gate)
3. `info()` inline + direct nop sink
4. suspend `record()` audit inline
5. `recordNow()` async enqueue
6. `recordNow()` buffered enqueue (sink excluded)
7. `recordNow()` fanned out to two per-sink pipelines
8. generated `@Log` wrapper dispatch
9. generated wrapper + compile-time metadata extraction
10. INLINE audit into a rotating `TextFileSink`

The wrapper cases use `BenchRepository.kt` and the real KSP output
(`:benchmark` applies `:ilogger-ksp`), so wrapper overhead is measured on
generated code, not a hand-written adapter. The `allopen` plugin opens
`@State` classes for JMH.

## Deterministic harness (quick validation)

```bash
.\gradlew.bat :benchmark:run
```

Warmup 3, measurement 7, n = 100_000 per iteration. Interprets the four core
engine cases in a few seconds with zero extra methodology — for relative
ordering, not absolute budgets.

## Budget gate

`BudgetGateTest` is the CI enforcement point. It is skipped unless
`ILOGGER_BENCH_GATE=true`, takes the median over nine 100k-event runs, subtracts
the empty-loop baseline from the disabled path, and fails when a median exceeds
its `Budgets.kt` ceiling by more than 15%:

| Path | Budget |
|---|---:|
| disabled logger | 2 ns/op |
| async enqueue | 1,000 ns/op |
| buffered audit enqueue | 3,000 ns/op |

```bash
ILOGGER_BENCH_GATE=true ./gradlew :benchmark:test --tests benchmark.BudgetGateTest
```

The `Benchmark — producer budgets` CI job runs exactly that command on Linux.
JMH remains the authoritative methodology; the gate exists so an accidental
slowdown fails a build instead of merely showing up in a graph. Local Windows
runs are informational — they are not the enforcement environment.

Numbers and interpretation live in
[docs/25-performance-and-benchmark.md](../docs/25-performance-and-benchmark.md).
