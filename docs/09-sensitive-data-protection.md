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

- Property, class, parameter, receiver and type-use annotation; it changes
  nothing at runtime by itself.
- It's a *contract* the processor enforces at every selection site: values
  under this annotation cannot appear in events without an explicit policy.

```kotlin
@LogSensitive token: String                 // parameter annotation
token: @LogSensitive String                 // type-use annotation
fun issue(): @LogSensitive String           // the whole return value
fun @receiver:LogSensitive Task.audit()     // extension receiver
```

A mark at the root of a selection (parameter or return type) covers the whole
chain; a property or class mark covers every selection that walks through it.

## Layer 3: chosen policies at the selection site

`LogMeta(..., sensitive = SensitiveHandling.X)`:

| Policy | Intended effect | In v1 |
|---|---|---|
| `DENY` (default) | selecting a sensitive value is a compile error | Yes |
| `MASK` | value replaced by the fixed mask `"***"` | Yes — rendered as `LogMasking.MASK` in generated code |
| `HASH` | truncated SHA-256 fingerprint recorded instead of the value | Yes — rendered as `LogMasking.hash(...)` |
| `OMIT` | entry excluded from the event entirely | Yes — no key is generated, no converter runs, no allocation happens |

A container-level `@LogConfig(sensitive = MASK|HASH|OMIT)` supplies this policy
for selections that carry none: a `LogMeta` selecting a `@LogSensitive` value
without an explicit policy compiles to the class policy instead of erroring.
With class-wide `OMIT`, sensitive-touched entries disappear from the metadata
map while every other selection logs normally. Converter-bearing entries
(`extractor`/`adapter`) keep their own log-safe shape, and entity ids /
`LogChange` values still refuse sensitive sources regardless of the class
policy — see
[04-log-annotation-reference.md](04-log-annotation-reference.md).

An explicit `LogMeta(..., sensitive = OMIT)` always drops that entry. That is
how you document an omission next to the selections that are logged — the
generated map contains no placeholder key, so consumers cannot confuse
"omitted" with "present but null".

### Actor overrides

`@Log(actor = "some.expression")` follows the container policy too: a
sensitive actor under `MASK`/`HASH` is recorded as the mask or fingerprint, an
actor under `OMIT` is dropped (events fall back to the ambient actor, if any),
and under the default `DENY` the selection is a compile error. Actor values
are never entities or ids, so no other policy is allowed.

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
An extractor/adapter + `OMIT` is also rejected: the entry is dropped, so the
converter would never run.

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

## Runtime payload validation (strict mode)

Compile-time selection covers generated instrumentation; manual
`record(...)`/`recordNow(...)` calls can still pass arbitrary objects in
`metadata` or `changes`. Strict mode validates those payloads at admission:

```kotlin
ILogger.create {
    strictMode = if (BuildConfig.DEBUG) StrictMode.WARN else StrictMode.OFF
    strictMaxStringLength = 1_024
    strictMaxEntries = 64
    strictMaxEventBytes = 65_536
}
```

Accepted values are `null`, strings, chars, booleans, numbers (including
unsigned types), enums, `LogSafeValue` wrappers, `UUID`, `Instant`, `Duration`,
`BigDecimal`, `BigInteger`, and (nested up to four levels) maps with string
keys, lists, arrays and unsigned arrays. Anything else is rejected:

- `WARN` — the event is dropped and reported through `onDrop` with
  `DropReason.STRICT_REJECT`;
- `THROW` — same, and the `record*` call throws `IllegalArgumentException`.
  Use only in development: instrumentation must not decide whether a business
  operation succeeds.

Explicitly opt a value in when you know its `toString` is safe:

```kotlin
metadata = mapOf("report" to LogSafeValue.of(reportSummary))
```

The wrapper is kept in the event so sinks can recognize the opt-in; strict
mode cannot inspect the object's internals, so wrapping a type that prints
secrets still leaks them.

Generated wrappers call `record` exactly like manual code. With `THROW`, a
rejected success event therefore surfaces as an exception from the wrapper —
another reason to reserve `THROW` for development and use `WARN` in debug
builds.

Extractor output is the exception: it uses the same log-safe value model but
is validated **always**, whatever the strict mode. A throwing or unsafe
extractor result degrades to a `null` metadata value instead of dropping the
event (see [10-extractors.md](10-extractors.md)).

## Runtime redaction (manual payloads)

Strict mode checks the *shape* of manual payloads; redaction scrubs their
*content*. A `RedactionPolicy` applies `RedactionRule`s at admission — after
value rendering, before limits, sampling and sinks — so no sink ever sees the
raw value:

```kotlin
ILogger.create {
    redaction = RedactionPolicy.build {
        rule(RedactionRules.secretKeys())     // password/token/cookie/... → "***"
        rule(RedactionRules.email())          // ada@example.com → "***"
        rule(RedactionRules.card())           // Luhn-verified card numbers only
        rule(RedactionRules.phone())
        rule(RedactionRules.bearer())         // "Bearer <jwt>" → "Bearer ***"
        rule("payments", RedactionRules.card())  // category-scoped
        // strict = true  // a throwing rule rejects the event instead of being skipped
    }
}
```

Rules apply to metadata values, change before/after values and actor
attributes. Key-based rules match the **full key path** — a nested map under
`credentials` masks every value inside it — while regex rules ignore keys and
scan the string itself. Everything is deterministic and thread-safe: rules are
stateless, the policy is immutable, and the built-in rules use bounded regexes
(no catastrophic backtracking). Scope details:

- Top-level identifiers (`action`, `entity`, `entityId`, `source`,
  `category`) and actor `id`/`name` are **not** redacted — they are public
  identifiers by design; keep secrets out of them at call sites.
- `LogSafeValue` wrappers are left untouched (they are the explicit "my
  toString is safe" opt-in).
- Nested containers are redacted down to depth 4, mirroring strict mode.
- Without a policy the whole stage is a zero-cost pass-through (one null
  check per event).

Because redaction runs **before** `limits`, a masked value counts toward the
byte budget — truncation never cuts a secret in half and leaves the fragment
for the sink. With `LimitAction.REJECT` an oversized event is dropped before
redaction would even matter; with `TRUNCATE` what survives truncation has
already been masked.

Ordering summary: value adapters → source suppression → **redaction** →
limits → sampling → strict validation → allocation.

The compile-time layers (masking, hashing, OMIT) remain the primary defense
for generated instrumentation; runtime redaction is the net for manual
payloads. Both compose: masked/hashed values contain no pattern for the
redaction rules to find.


