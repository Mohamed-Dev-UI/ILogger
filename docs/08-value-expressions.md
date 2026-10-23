# 08. Value expressions

Metadata, entity ids, actor overrides and change tracking all use one small
compile-time **source grammar**. Every expression is resolved by KSP against
the real function signature; anything unresolvable fails compilation with
"known sources" context (see [23-diagnostics-catalog.md](23-diagnostics-catalog.md)).

## The grammar

| Expression | Meaning |
|---|---|
| `result` | the function's return value |
| `result.prop` | property of the return value |
| `paramName` | a parameter (whole value; loggable types only) |
| `paramName.prop` | property of a parameter |
| `'literal'` | compile-time constant string |
| `actor` | the resolved actor's id |
| `actor.name` / `actor.type` / `actor.<attribute>` | actor fields |
| `context.key` | ambient `LogContext.attributes["key"]` |

## Rules the processor enforces

1. **Vararg parameters** can only be referenced as a whole *with an extractor*
   (`LogMeta("tasks", extractor = ..., name = "count")`); chained access into
   a vararg (`tasks.first().title`) is a compile error: extracting a value
   from a *collection* argument makes the audit non-reproducible.
2. **`result` only exists on SUCCESS** — on START/FAILURE events the
   result-derived values are absent by construction. Choose `before = true`
   and value expressions accordingly.
3. **Types** — selected values must be *loggable*. The default model accepts:
   - scalars: `String`, `Char`, all primitive numbers, unsigned integers
     (`UInt`/`ULong`/`UShort`/`UByte`), `Boolean`;
   - enums (rendered as their name);
   - `java.util.UUID`, `java.time.Instant`, `java.time.Duration` (rendered as
     their canonical `toString()`), `java.math.BigDecimal`/`BigInteger`;
   - **value classes** whose underlying type is loggable (rendered via
     `toString()` unless a [ValueAdapter](10-extractors.md) reshapes them);
   - one-level containers of the above: `List`/`Set`/`Iterable`, arrays
     (including primitive arrays like `IntArray`), and `Map` with loggable
     keys and values;
   - `Result<T>` where `T` is loggable (unwrapped like nullable returns).
   Arbitrary domain objects are never serialized wholesale. Selection of a
   specific property ("task.title") keeps payloads small and PII-scoped by
   design. Types outside the model need `LogMeta(extractor = ...)` or
   `LogMeta(adapter = ...)` (see [10-extractors.md](10-extractors.md)).
4. **Sensitive names** (`password`, `token`, `accessToken`, `cookie`, ...) are
   refused by name — even without annotations — as defense in depth for
   dynamic metadata; explicit `sensitive =` policy unlocks a typed selection
   ([09-sensitive-data-protection.md](09-sensitive-data-protection.md)).

## Examples

```kotlin
// constants name the key; the source expression names the value:
LogMeta("'closure'", name = "event")       // metadata["event"] = "closure"
LogMeta("context.requestId")               // key defaults to "requestId"
LogMeta("task.title")                      // key defaults to the last path segment
LogMeta("title", name = "myKey")           // explicit key rename
LogMeta("result.title")                    // from the returned object (SUCCESS only)
LogMeta("actor.team")                      // actor attribute — correlated by design
```

The resulting event metadata in the above order:

```
{event=closure} ; {requestId=...} ; {title=...} ; {myKey=...}
```

Note: in v1 a constant expression (`'literal'`) **requires** an explicit
`name =` — otherwise the processor reports
`LogMeta('...') needs an explicit name.`

## Ways to produce one metadata value

| Option | Example | Simplicity | Runtime cost | Dev speed | Verdict |
|---|---|---|---|---|---|
| Property chain | `LogMeta("task.title")` | Minimal — one path | One property read | Fastest | **🏆 Default choice** |
| Constant literal | `LogMeta("'v2'", name = "pipeline")` | Minimal | Zero (constant) | Fast | Labeling/annotations on events |
| `context.*` / `actor.*` | `LogMeta("context.tenantId")` | Minimal | One map lookup | Fast | Correlation-style context |
| Custom extractor | `LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")` | Needs a class | A per-wrapper instance call | Slower — extra class | Derived/aggregate values the compiler can't express (see [10](10-extractors.md)) |

**Recommended:** property chains by default (proven, cheapest, PII-scoped);
reach for an extractor only when the value must be *computed*; constants only
for labels that never vary per call.

## What the processor knows (`log.trace` example)

The example's `context.requestId` became metadata `{requestId=...}`:
`context.*` expressions trace ambient scope attributes, never arbitrary
thread-locals — the same scope discipline as actor/correlation ids.
