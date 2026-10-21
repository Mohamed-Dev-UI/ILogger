# 13. Categories & sink routing

Sinks are bound with an optional **category** key — the routing unit between
"the audit trail" and "the diagnostics noise".

## Binding

```kotlin
ILogger.create {
    // catch-all (category null/default): everything
    sink(ConsoleLogSink())

    // only audit-category events reach durable, machine-readable storage
    sink(TextFileSink(file = auditJsonl, codec = JsonEventCodec), category = "audit")
}
```

Binding rules:

1. Exactly **one catch-all** binding is recommended; `null` is the default.
2. A category binding delivers **only** events with that category; a catch-all
   delivers *everything*, category bindings are additive.
3. Events without `category` go to the catch-all only.

## Routing strategies compared

| Strategy | Shape | Simplicity | Production cost | Verdict |
|---|---|---|---|---|
| Single catch-all sink | `sink(s)` | Simplest; one destination per event decision | One pipeline; hard to mix audiences | Start here in small apps |
| Catch-all + category bindings | `sink(console)` + `sink(file, "audit")` | Two lines | Durable trail isolated from noise | **🏆 Recommended** (catch-all for dev/console, categories for durable paths) |
| Per-category everything | K category bindings, no catch-all | More wiring; unmatched events are silently dropped everywhere | Highest performance on hot paths (fewer sinks see each event) | High-volume domains (`http`, `db`) with strict audiences |
| `CompositeLogSink` fan-out inside one binding | `sink(CompositeLogSink(console, file))` | One binding, many sinks | Composite internal loop; a shared error-mode | Rarely needed — separate bindings usually compose better (see [14](14-sinks.md)) |

**Recommended:** catch-all for developer/ops visibility, plus narrow
category bindings for the durable, machine-readable store. Events without a
category then stay observable without ever touching the strict path.

## Categories in annotations

```kotlin
@Log(
    action = TaskActions.CLOSED,
    category = "workflows",
    level = LogLevel.INFO,
    metadata = [LogMeta("task.id"), LogMeta("'closure'", name = "event")],
)
suspend fun close(taskId: Long)
```

Applications and integrations pick categories explicitly. In the shipped
integrations, `@Log(category = ...)` annotations set their own strings; the
Ktor plugin and Room lifecycle events record **without** a category (they go
to the catch-all) — so a `"audit"`-category durable sink stays exactly as
strict as you configured it.

## The privacy aspect

Category routing is how you keep **diagnostic noise out of durable audit
files**: the `examples/custom-sink` demo writes an INFO/DEBUG/WARN/ERROR full
console catch-all while the JSONL file — with rotation — receives only the
audit category. Then even a misconfigured `includeArguments`-style leak would
not reach the machine-readable durable path.

## What NOT to use categories for

- Level filtering (levels are for min-level floors, not routing).
- Environment segmentation (use separate logger instances in separate hosts).
- Per-tenant isolation — use separate sinks/instances; categories are an app
  design decision, not data isolation.
