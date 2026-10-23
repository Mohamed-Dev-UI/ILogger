# 39. Migrating to ILogger

ILogger can be adopted incrementally. You do not have to replace an existing
logging stack on day one: the bridges can ingest existing SLF4J/JUL traffic
while you migrate high-value operations to `@Log`, and the sinks can run in
parallel with your current destination until you trust the new pipeline.

This guide covers the common starting points:

1. ad-hoc `println` / `android.util.Log` / string logging,
2. SLF4J + Logback (or `java.util.logging`),
3. another structured logger or a hand-rolled audit table.

## 1. From ad-hoc logging

**Before** (audit-relevant facts are buried in text):

```kotlin
fun closeTask(taskId: String, reason: String) {
    try {
        repository.close(taskId)
        Log.d("Tasks", "closed $taskId because $reason")
    } catch (error: Exception) {
        Log.e("Tasks", "close failed for $taskId", error)
        throw error
    }
}
```

**After** — the operation carries the contract:

```kotlin
interface TaskRepository {
    @Log(
        action = "task.closed",
        entity = Task::class,
        entityId = "taskId",
        metadata = [LogMeta(value = "taskId", name = "task.id"), LogMeta("reason")],
    )
    suspend fun close(taskId: String, reason: String)
}
```

```kotlin
val repo = RoomTaskRepository(db).logged(logger)
```

What the generated adapter records without further code: actor (from the
ambient context), entity type and id, metadata, `source` (the implementation
method), outcome, and the failure — while rethrowing the original exception.
`Log.e`/`Log.d` can stay for developer-facing diagnostics; they are not audit
records.

Rules of thumb:

- Move **state changes** and **access decisions** to `@Log` first; leave
  chatty debug traces on the diagnostic methods.
- Action names are stable identifiers: `domain.pastTense` (`task.closed`),
  never interpolated values. Bump `version` when the payload changes.
- Metadata keys are stable and flat (`task.title`, not the whole `Task`).

## 2. From SLF4J / Logback / JUL

Two directions, both supported in 1.0.0:

| Direction | Adapter | Use when |
|---|---|---|
| Existing logs → ILogger | `ILoggerAppender` (Logback) / `JulLogHandler` (JUL) | You want one structured pipeline and audit sink for everything |
| ILogger → existing logs | `Slf4jLogSink` / `JulLogSink` | You already have a log shipper and only want ILogger to feed it |

Inbound mapping (see [34](34-logging-bridges.md)):

| Source | Event field |
|---|---|
| Logger name | `action = "$actionPrefix.$loggerName"`, `source = loggerName` |
| Level | `LogLevel` (`INFO`/`WARNING`/`SEVERE` mapped to ILogger levels) |
| Formatted message | `metadata.message` (JUL also stores `metadata["param.N"]`) |
| MDC | `metadata["mdc.*"]` |
| SLF4J 2 key/value pairs | `metadata["kv.*"]` |
| Throwable | `failure` |

```kotlin
// Logback: route an existing logger into ILogger
val appender = ILoggerAppender(logger, actionPrefix = "legacy.")
```

**Pick one direction per source.** Running an inbound appender while the same
code also writes to the original Logback file duplicates every event; running
inbound and outbound adapters on the same logger can recurse. ILogger detects
direct recursion inside a sink and drops it, but a chain A → B → A is a
configuration bug.

JUL consumers use `JulLogHandler`/`JulLogSink` with the same shape. There is no
bundled Timber adapter: keep Timber for UI diagnostics, or replace
`Timber.*` calls with ILogger diagnostics and move audit facts to `@Log`.

## 3. From a hand-rolled audit table or structured logger

If you already emit structured records, map them to the event model rather
than rewriting the producers:

| Existing concept | ILogger |
|---|---|
| `event_type`, `operation` | `action` (+ `actionVersion`) |
| `user_id`, `principal` | `Actor.user(id, name)` |
| `entity_type`, `entity_id` | `entity`, `entityId` |
| Before/after pairs | `FieldChange(field, before, after)` |
| Request id / trace id | `LogContext` correlation id |
| Error object | `failure` (type, message, frames) |
| Free-form JSON | `metadata` (loggable scalars only) |

For code that cannot be annotated (dynamic dispatch, third-party boundaries),
use the manual API — it produces the same `LogEvent`:

```kotlin
logger.record(
    action = "export.completed",
    entity = "Export",
    entityId = exportId,
    metadata = mapOf("rows" to rowCount, "format" to "csv"),
)
```

`record` is suspend and respects backpressure. `recordNow` is the non-suspend
variant for callbacks and synchronous DAOs; under `INLINE` dispatch it waits
(bounded) for the sink, otherwise it admits the event to the pipelines.
`recordBlocking` adds an explicit timeout and returns whether the event was
delivered or accepted ([04](04-log-annotation-reference.md),
[16](16-dispatch-backpressure-error-modes.md)).

## Adoption plan

1. **Install the pipeline** with a console sink in development and a file sink
   in staging. Add the actor extractor and correlation once, at construction
   ([11](11-actors-and-ambient-context.md), [12](12-correlation-and-causation.md)).
2. **Bridge existing logs** if you need one destination immediately. Verify
   there is no duplicate path before enabling it in production.
3. **Annotate one repository interface** that owns the most important state
   changes. Run its tests with `TestKit` and a `RecordingSink`; assert action,
   entity id, actor and failure ([18](18-testing.md)).
4. **Expand by risk**: authentication/authorization, money movement, data
   export/deletion, then the rest. Do not annotate everything in one PR.
5. **Add audit storage** when the event contracts are stable: Room or a file
   sink, then integrity/encryption/retention as required
   ([30](30-integrity.md), [31](31-encryption-at-rest.md), [32](32-compliance.md)).
6. **Close the loop**: capture the action manifest in CI (`schema`) and add
   `ilogger verify` to the pipeline that ships audit files ([36](36-cli.md)).

## Common migration mistakes

| Symptom | Cause | Fix |
|---|---|---|
| Duplicate events | Bridge plus manual record, or inbound + outbound on one logger | Keep one direction; remove the duplicate producer |
| Event has no actor | No ambient actor set and no extractor registered | Wrap the request/session in `LogContext` or add an extractor |
| Metadata is empty | Values are domain objects, not loggable scalars | Register a `ValueAdapter`, or select scalar fields in `LogMeta` |
| Action rejected at runtime | Dynamic or blank action | Use a constant; actions are stable identifiers |
| Audit file cannot be verified | Records were edited or the chain was concatenated from segments without keys | Verify keyed with the right keyring; never hand-edit JSONL |
| Missing correlation between client and server | Only one side creates the context | Propagate the header or use the Ktor plugin ([20](20-ktor-integration.md)) |

## Compatibility and rollback

Migrating does not change your public API: `.logged()` returns the same
interface type. Removing ILogger later means dropping the adapter call — the
domain code is untouched. Keep the old logging stack in place until the new
sink has been observed for a full release cycle; the cost of dual-running is
disk space, not correctness.
