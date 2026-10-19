# `:benchmark` — overhead measurement

Two harnesses measure per-event costs of the engine paths:

## JMH suite (primary)

```bash
.\gradlew.bat :benchmark:mainBenchmark      # 5×1s warmup + 7×1s measurement
.\gradlew.bat :benchmark:mainQuickBenchmark # quick validation profile
```

`EnginePathsBenchmarks` (`src/main/kotlin/benchmark/JmhEngines.kt`) runs the
four engine paths — disabled logger, `info()` inline, suspend `record()`
audit inline, `recordNow()` async enqueue — under forked-JMH methodology via
kotlinx-benchmark. The `allopen` plugin opens `@State` classes for JMH.

## Deterministic harness (quick validation)

```bash
.\gradlew.bat :benchmark:run
```

Warmup 3, measurement 7, n = 100_000 per iteration. Interprets the same four
cases in a few seconds with zero extra methodology — for relative ordering,
not absolute budgets.

Numbers and interpretation live in
[docs/25-performance-and-benchmark.md](../docs/25-performance-and-benchmark.md).
