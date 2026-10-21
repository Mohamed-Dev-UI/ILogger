# 09. Sensitive data protection

Audit trails are exactly where personal data ends up by accident. ILogger's
protection is layered, **compile-time first**.

## Layer 1: name-based refusal (defense in depth)

Values selected from properties named — even *without* any annotation —
`password`, `passwd`, `pwd`, `token`, `secret`, `apiKey`, `privateKey`,
`credential(s)`, `sessionKey`, `refreshToken`, `accessToken`,
`authorization`, `cookie` are refused/unverifiable by name. This protects
dynamic metadata paths; it produces a warning (suspiciousName), which only an
explicit policy at the selection site can resolve.

## Layer 2: `@LogSensitive` — the contract

```kotlin
data class Task(
    val id: Long,
    val title: String,
    @LogSensitive(reason = "demo: values like this must be hashed before any sink")
    val passwordHash: String = "",
)
```

- Property/class annotation; it changes nothing at runtime by itself.
- It's a *contract* the processor enforces at every selection site: values
  under this annotation cannot appear in events without an explicit policy.

## Layer 3: chosen policies at the selection site

`LogMeta(..., sensitive = SensitiveHandling.X)`:

| Policy | Intended effect | In v1 |
|---|---|---|
| `DENY` (default) | selecting a sensitive value is a compile error | Yes |
| `MASK` | value replaced by the fixed mask `"***"` | Yes — rendered as `LogMasking.MASK` in generated code |
| `HASH` | truncated SHA-256 fingerprint recorded instead of the value | Yes — rendered as `LogMasking.hash(...)` |
| `OMIT` | value excluded from the event entirely | Not selectable in practice — an omitted selection is a contradiction, so `OMIT` currently compiles to the `DENY` behavior. Treat it as unavailable |

## Comparing the usable policies

| Criterion | `DENY` | `MASK` | `HASH` |
|---|---|---|---|
| Data appears in events | Never | Never (constant string) | Fingerprint only |
| Privacy | Strongest | Strongest | Strong (non-reversible) |
| Analytical value | None — absence visible at compile time | Low (presence flag) | Identity correlation: same value → same hash |
| Reversible | — | No | No (24-hex-character SHA-256 prefix) |
| Cross-process stability | — | Trivially | Yes — deterministic digest, stable across processes |
| Cost | Zero at runtime; compile-time friction | Zero | A SHA-256 per recorded field |
| Recommended when | The value should never be logged — the default stance | Presence/absence matters more than content | You must correlate the same actor/credential across events without exposing it |

## Runtime API: `io.ilogger.core.LogMasking`

The transforms generated code applies are public and stable — reuse them when
your own code must match event semantics:

```kotlin
LogMasking.MASK                      // "***"
LogMasking.hash("secret")            // first 24 hex characters of SHA-256 (null in → null out)
LogMasking.HASH_LENGTH               // 24
```

`hash` is deterministic and non-reversible: the same input always yields the
same fingerprint, so events can be correlated acrosss time without exposing
the value.

## Layer 4: `@LogExclude` — never logged, not even masked

Some values must never reach a sink: `@LogExclude(reason = "...")` makes the
processor reject every reference in metadata/entity-id/changes.

## Combination rule

An extractor + sensitive=MASK/HASH cannot be combined (the extractor's output
shape is application-defined, so ILogger cannot guarantee the policy) — this
is a compile error with a suggestion to apply the policy inside the extractor.

## Worked example (from `:app`)

```kotlin
data class Task(
    val id: Long,
    val title: String,
    @LogSensitive(reason = "Private notes may contain personal data")
    val privateNote: String = "",
)

@Log(
    action = TaskActions.ARCHIVED,
    schemaVersion = 2,
    category = "audit",
    metadata = [
        // The same value, explicitly selected under two policies.
        LogMeta("task.privateNote", name = "noteFingerprint", sensitive = SensitiveHandling.HASH),
        LogMeta("task.privateNote", name = "noteMasked", sensitive = SensitiveHandling.MASK),
        LogMeta("task.title"),
    ],
)
suspend fun archive(task: Task): Boolean
```

Event on console (text codec) shows only the projections — never the raw note;
the JSONL file stores the same hash and mask:

```json
"metadata":{"noteFingerprint":"<24-hex-digest>","noteMasked":"***","title":"repair coffee machine #1"}
```

The `:app` Protection screen emits exactly this event and checks that the raw
note does not occur anywhere in the event JSON.

## Runtime sinks: less privileged than compile-time

The JSON codec or custom sinks can only serialize what the *compiled* event
already contains — no sink-side hook can decode a masked/hashed value:
post-generation, data protection cannot be undone by a sink misconfiguration.
