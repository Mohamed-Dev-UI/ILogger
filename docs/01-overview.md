# 01. Overview & design priorities

ILogger is a standalone Kotlin logging **and audit** engine. It differs from
general-purpose logging frameworks by treating "who did what to which entity"
as the primary artifact, not a side note.

## The two layers

**Runtime** (`ilogger-core`) is a small, framework-independent engine:
`ILogger`, secrets-free structured events, sinks, dispatch/backpressure and an
ambient actor context built on `kotlin.coroutines`. It depends only on
kotlin-stdlib, `kotlinx-coroutines-core` and the annotations module.

**Compile time** (`ilogger-ksp` + `ilogger-annotations`) turns a `@Log` on an
interface function into a generated adapter. The generated code is
human-inspectable Kotlin: no reflection, no runtime proxies, no bytecode
magic.

```kotlin
// what you write
@Log(action = TaskActions.COMPLETED, entity = Task::class)
suspend fun complete(id: Long)

// what codegen targets (simplified) — one event after the operation succeeds
suspend fun complete(id: Long) {
    try {
        delegate.complete(id)
        logger.record(
            action = "task.completed",
            level = LogLevel.AUDIT,
            entity = "Task",
            entityId = id.toString(),
            source = "TaskDao.complete",
        )
    } catch (e: Throwable) {        // failure event; the exception rethrows
        logger.record(..., outcome = Outcome.FAILURE, failure = e)
        throw e
    }
}
```

## Design priorities, in order

1. **Audit correctness.** Events carry an action, outcome, actor, entity, id,
   correlation and causation identities and (on failure) a structured
   `LogFailure`. Success events are emitted *after* the operation succeeds, so
   "we said we did it" always reflects reality.
2. **Ambiguity is a compile error, never a guess.** If the entity id cannot be
   proven from signatures (result, `id` property, single id-typed parameter),
   the processor rejects the annotation with an actionable "Fix:" text.
3. **Bounded memory.** Async dispatch runs through a fixed-size channel.
   Overflow policy (`DROP_OLDEST`, `DROP_NEWEST`, `BLOCK`, `FAIL`) is explicit
   configuration; silence about capacity is not allowed.
4. **Failure transparency.** Sink exceptions follow per-level `ErrorMode`s —
   `RETRY` for audit/error, drop-with-diagnostic for diagnostics — and never
   corrupt the application's control flow: `@Log` wrappers rethrow.
5. **Framework independence.** Room, Ktor and JSON integration are optional
   modules; core needs nothing but stdlib and coroutines.
6. **Explicit over implicit.** Metadata is selected with compile-time value
   grammar; domain objects are never serialized wholesale. Sensitive values
   cannot enter events without a chosen policy (`MASK`/`HASH`/...).

## Where the guarantees come from

- **Generated wrappers** delegate via Kotlin interface delegation; functions
  without `@Log` pass through untouched.
- **Suspend vs non-suspend:** suspend functions record via suspend `record()`
  (reads the coroutine-scoped ambient context); non-suspend functions record
  via `recordNow()` (thread-local bridge only). This rules out racy implicit
  context.
- **The engine** has exactly one job: move valid `LogEvent`s to their sinks,
  under bounded memory, with honest failure semantics.

## Adoption strategies compared

| Strategy | Shape | Audit guarantees | Dev speed | Maintenance | Verdict |
|---|---|---|---|---|---|
| Codegen-first (annotate interfaces) | `@Log` on repository/DAO/use-case interfaces; engine wired once | Strongest — proven at compile time | Slower first day, fastest every day after | Lowest — contract-owned | **🏆 Recommended** |
| Hybrid (codegen + manual boundaries) | `@Log` core ops; `record()` for streams/legacy | Strong where annotated | Medium | Medium | Realistic end-state for large apps |
| Manual-only (`record`/`recordNow` everywhere) | Engine + call-site code | As good as discipline allows | Fast first day, slow later | Highest | Acceptable to start with zero build config; migrate opportunistically |
| Engine-only (no events) | Sinks + diagnostics `trace..error` | None (diagnostics only) | Fastest | Low | Observability shells, prototypes |

Full comparison of the recording instruments:
[27-manual-instrumentation.md](27-manual-instrumentation.md). The recommended
instrumentation strategy in practice: **annotate domain operations with
`@Log`, consult `trace..error` for diagnostics, `record` only where codegen
cannot reach.**

## Reading order

Cheatsheet (40) → quickstart (02) → annotation reference (04) → value
grammar (08) → sinks/routing (13, 14) → configuration (26) → integrations
(16, 19, 20). Testing (18) early; the diagnostics catalog (23) when the
compiler talks back; manual instrumentation alternatives (27) before
bypassing the processor. Moving an existing codebase: migration (39).
Choosing versions or targets: compatibility (38) and KMP (37). Operations:
integrity (30), encryption (31), CLI (36).
