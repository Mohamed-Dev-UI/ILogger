# 24. Limits & constraints

Deliberate constraints — each is a design decision, not a missing feature.

## What ILogger does not do

| Limit | Reason |
|---|---|
| No log-file shipping built in | sinks own transport; a collector sink in your app integrates any pipeline |
| No runtime reflection anywhere | compile-time proof is the product; reflection would erase the guarantees |
| No database diffs | `LogChange` from/to takes caller-provided values — DB diffs would be slow + wrong |
| No thread-local actor leak into coroutines | suspend vs non-suspend context split is documented and total ([05](05-event-lifecycle.md)) |
| No automatic module adoption | Room/Ktor/serialization are opt-in modules; core fabric stays framework-free |
| No unbounded buffers | every async pipeline has `bufferSize` and an explicit overflow policy |
| No silent entity-id guesses | ambiguity always produces a compile error with a Fix |
| No non-const action expressions | actions are public identifiers, defined once |

## Version & tooling pins

- Kotlin 2.2.10 / KSP 2.3.12 / KotlinPoet 2.2.0 (2.4 metadata unreadable by
  the 2.2.x compiler).
- Room 2.8.5 (KMP), sqlite-bundled 2.7.1 for the JVM Room example.
- Ktor 3.6.0 server core; AGP 9.4.1 for the demo app.

## Known platform limits

1. **Room's `setQueryCallback`** is Android-source-only upstream, hence not
   wrapped in `ilogger-room` (which stays JVM+Android clean).
2. **Room's JVM database builder** requires an absolute file path + bundled
   driver; in-memory variants exist for tests.
3. **Ktor `CallSetup`/`ResponseSent`/`CallFailed`** note: one failing handler
   that sends a 500 produces both failure-hook and response-hook events
   (two events per hard failure) — an honest, acceptable duplication until
   RequestTracing matures.
4. **Negative diagnostics fixtures** now run in the `:ksp-diagnostics` module
   behind a processor fixture mode (`ilogger.expect-diagnostics`) rather
   than a full compile-testing harness — verbatim-message assertions, but
   still no incremental-compilation round-trips.

## Roadmap candidates

- Full compile-testing harness (incremental-compilation round-trips beyond
  the fixture-mode assertions).
- `expect/actual` context for multiplatform `LogContexts` (current JVM/Android
  thread implementation is fine; iOS needs a worker-model variant).
- Schema registry tooling (export actions + versions from compiled outputs).
