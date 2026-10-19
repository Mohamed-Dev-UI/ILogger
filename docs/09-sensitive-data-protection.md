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

| Policy | Effect |
|---|---|
| `DENY` (default) | selecting a sensitive value is a compile error |
| `MASK` | value replaced by a mask; content is never recorded |
| `HASH` | SHA-style digest recorded instead of the value |
| `OMIT` | key present design no value; useful for "this field exists" |

## Layer 4: `@LogExclude` — never logged, not even masked

Some values must never reach a sink: `@LogExclude(reason = "...")` makes the
processor reject every reference in metadata/entity-id/changes.

## Combination rule

An extractor + sensitive=MASK/HASH cannot be combined (the extractor's output
shape is application-defined, so ILogger cannot guarantee the policy) — this
is a compile error with a suggestion to apply the policy inside the extractor.

## Worked example (from `:app`)

```kotlin
metadata = [
    LogMeta("task.title"),
    LogMeta("task.passwordHash", name = "passwordHashHashed",
            sensitive = SensitiveHandling.HASH),
],
```

Event on console (text codec) shows a hashed blob — never the raw value; the
JSONL file stores the same hash:

```json
"metadata":{"title":"repair coffee machine #1","passwordHashHashed":"<digest>"}
```

## Runtime sinks: less privileged than compile-time

The JSON codec or custom sinks can only serialize what the *compiled* event
already contains — no sink-side hook can decode a masked/hashed value:
post-generation, data protection cannot be undone by a sink misconfiguration.
