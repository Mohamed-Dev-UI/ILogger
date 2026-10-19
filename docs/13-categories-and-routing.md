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

Host integrations pick their own categories: the Ktor plugin uses `"http"`,
Room lifecycle events use `"audit"`-oriented routing, custom apps invent
domain categories (`billing`, `admin`, `dev-support`).

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
