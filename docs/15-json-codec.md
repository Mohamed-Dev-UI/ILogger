# 15. JSON codec (`ilogger-serialization`)

The optional `ilogger-serialization` module ships `JsonEventCodec` — a shape-
stable, single-line JSONL encoder built on kotlinx-serialization JSON
primitives.

## Usage

```kotlin
dependencies {
    implementation(project(":ilogger-serialization"))
}

val logger = ILogger.create {
    sink(
        TextFileSink(
            file = Path("build/audit.jsonl"),
            codec = JsonEventCodec,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 64 * 1024, maxFiles = 5),
        ),
        category = "audit",
    )
    sink(ConsoleLogSink())
}
```

## Output shape

Every optional member (`actor`, `entity`, `entityId`, `source`, `category`,
`correlationId`, `causationId`, `metadata`, `changes`, `failure`) is **omitted
entirely when absent** — receivers never receive half-invented defaults:

```json
{"id":"78d40b44-...","timestampMillis":1792327693287,"level":"AUDIT","action":"order.placed",
 "actionVersion":1,"outcome":"success","entity":"Order","entityId":"o-1001",
 "source":"example.customsink.main","category":"audit",
 "metadata":{"total":42.5,"currency":"EUR"}}
```

With actor/attribution/failure:

```json
{"level":"ERROR","action":"http.request","outcome":"failure",
 "actor":{"id":"u-9","type":"user"},
 "failure":{"errorType":"java.lang.IllegalStateException",
            "message":"payment gateway offline","stackSummary":[...]}}
```

## Value projection rules

Metadata values are projected onto JSON primitives:

| Type | Result |
|---|---|
| `String` | JSON string |
| `Boolean` | JSON boolean |
| `Number` | JSON number |
| `null` | `null` |
| anything else | `toString()` string (deterministic degradation, never throws) |

This last rule is why arbitrary extractor outputs (e.g. a `List`) cannot
corrupt a JSONL line.

## Consumers

- the codec never throws on unexpected value types;
- `toJsonElement(event)` is public for consumers who post-process
  (e.g. enrich before shipping to a collector);
- the fail message stack: `failure.stackSummary` is a capped array — payload
  size is bounded by design;
- the module is **encode-only**: there is no `decode`/round-trip parser —
  JSONL lines are meant for consumers, not reload into `LogEvent`.

Quick consumer recipes:

```bash
# one action's events, keyed fields
jq -c 'select(.schemaId=="task.created")' audit.jsonl

# failures with their root causes
jq -c 'select(.outcome=="failure") | {action, entityId, failure}' audit.jsonl

# all events of one story
jq -c 'select(.correlationId=="req-abc")' audit.jsonl
```

## Codec choice

| Codec | Output | Best for | Cost | Verdict |
|---|---|---|---|---|
| `DefaultTextEventCodec` | single human line | Console/logcat during development | Zero modules | Dev/ops console |
| `JsonEventCodec` | stable JSONL | Durable machine-readable trails, shipping | `ilogger-serialization` module | **🏆 Recommended** for every durable path |
| Custom `EventCodec` | your shape | Legacy pipelines (CSV), compaction, encrypted formats | One class to own | Only when a format is mandated |

`EventCodec` implementations must be side-effect free, fast and safe for
concurrent calls; the codec choice is per-sink, so console and JSONL
coexist naturally.

## When to use (and not use)

Use for durable, machine-readable audit files (fs, object storage, shipping to
collectors). Do **not** use for human terminal debugging — the text codec is
better there. Rotation policy lives with `TextFileSink`, not the codec.
