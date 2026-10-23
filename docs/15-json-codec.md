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

Metadata values are projected onto JSON:

| Type | Result |
|---|---|
| `String` | JSON string |
| `Boolean` | JSON boolean |
| `Number` | JSON number |
| `Char` | JSON string |
| `UInt`/`ULong`/`UShort`/`UByte` | JSON number (exact, via the unsigned overloads) |
| `java.util.UUID` | JSON string (`toString()`) |
| `java.time.Instant` | JSON string (ISO-8601) |
| `java.time.Duration` | JSON string (ISO-8601, e.g. `PT2H`) |
| `java.math.BigDecimal`/`BigInteger` | JSON number (exact `toString()` form) |
| `null` | `null` |
| one-level `List`/`Set`/`Iterable`/array | JSON array (elements projected by these rules) |
| one-level `Map` | JSON object (keys via `toString()`, values projected by these rules) |
| containers deeper than two levels | `"<max-depth>"` marker string (see below) |
| anything else (incl. value classes) | `toString()` string (deterministic degradation, never throws) |

Container projection has a depth guard: containers nested more than two
levels deep render the literal marker `JsonEventCodec.MAX_DEPTH_MARKER`
(`"<max-depth>"`) in place of the container, so cyclic or deeply nested
structures can never recurse unboundedly — the guard cuts the walk *before*
any `toString()` is attempted, which matters because a cyclic container's
`toString()` alone would overflow the stack. Scalar values are never
degraded by depth.

This last rule is why arbitrary extractor outputs cannot corrupt a JSONL
line — and registering a `ValueAdapter` for a value class is the supported
way to render it as its underlying value instead of `toString()`.

## Decoding (round trip)

`JsonEventCodec.decode(line)` parses one JSON line back into a `LogEvent` —
the inverse projection of the encoder. Required members: `id`,
`timestampMillis` and `action`; `level` defaults to `AUDIT`,
`actionVersion` to 1, `outcome` to `success`. Metadata comes back as plain
Kotlin values (`null`, `String`, `Boolean`, `Long`, `Double`,
`Map<String, Any?>`, `List<Any?>`). Malformed input (invalid JSON, a non-object
line, or a missing required member — including `change.field` and
`failure.errorType`) throws `IllegalArgumentException` naming the member.

The projection is lossy where encoding already is: `cancelled` failures decode
as regular failures, and containers deeper than the codec's structure depth
come back as the `"<max-depth>"` marker string.

Round-trip stability is what makes hash-chain verification work on JSONL
files: `decode` reproduces the canonical shapes `TrailVerifier` recomputes
stamps from — see [30](30-integrity.md).

```kotlin
val events = File("audit.jsonl").readLines()
    .filter { it.isNotBlank() }
    .map { JsonEventCodec.decode(it) }
```

## Consumers

- the codec never throws on unexpected value types **when encoding**;
  `decode` is strict instead — a malformed line fails loudly with its cause;
- `toJsonElement(event)` is public for consumers who post-process
  (e.g. enrich before shipping to a collector);
- the fail message stack: `failure.stackSummary` is a capped array — payload
  size is bounded by design.

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
