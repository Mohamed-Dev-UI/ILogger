# 05. Event lifecycle

Every `@Log` function emits **events in a fixed order**. The wrapper calls the
delegate first and only *then* reports success — success events never lie.

## Outcomes

| Outcome | Meaning | When recorded |
|---|---|---|
| `START` | operation was *about to* run | only with `before = true` |
| `SUCCESS` | operation completed normally | default (`onSuccess = true`) |
| `FAILURE` | operation threw | with `onFailure = true` |

## Cancellation

A `CancellationException` is caught before the generic failure branch:

| `onCancellation` | Event | Exception |
|---|---|---|
| `SKIP` (default) | none | rethrown by identity |
| `LOG` | FAILURE with `failure.cancelled = true` | rethrown by identity; any logging error is attached as a suppressed exception |

Cancellation events are recorded through `recordNow` with `ASYNC` admission so
a cancelled coroutine never blocks on delivery and logging can never mask the
cancellation.

## Order with `before = true`

```
START (before)  →  delegate runs  →  SUCCESS (after)
                              ↘ throws  →  FAILURE (onFailure) → rethrow
```

- The START event has **no result-derived values** (there is no result yet):
  `result`-sourced metadata is absent — it does exist syntactically but is
  resolved to `null`/absent on START and FAILURE.
- The FAILURE event carries the structured `LogFailure` (error type class
  name, message, root cause type, capped stack summary — no raw throwable in
  metadata) **and the exception is rethrown unchanged**: failure events never
  swallow exceptions.
- Changes (`LogChange`, and auto-captured fields) are recorded **on `SUCCESS`
  only** — a "before→after" pair cannot be trusted mid-crash. Auto-captured
  pairs are filtered to fields that actually changed, and explicit
  `onlyIfChanged` pairs are omitted when both values are equal.

## Suspend vs non-suspend paths

| Function | Generated call | Reads context from |
|---|---|---|
| `suspend fun` | `logger.record(...)` | the *coroutine context* (`LogContexts.current()`) |
| non-suspend `fun` | `logger.recordNow(...)` | the *thread-local bridge* only (`LogContexts.currentThread()`) |

These are deliberately different: a non-suspend call cannot read a coroutine
context, so implicitly "reusing" an actor scope would be racy. For non-suspend
dispatch inside actor scopes, establish the bridge explicitly:

```kotlin
LogContexts.onCurrentThread(LogContext(actor = actor)) {
    dao.findById(id)   // recordNow sees the bridge value
}
```

`recordNow` supports `INLINE` with a bounded blocking bridge: the event is
written before the call returns, or dropped as `SINK_FAILURE` when the sink
exceeds `blockTimeoutMillis`. Use `recordBlocking(...)` when you need an
explicit timeout and want to know whether delivery succeeded. Non-suspend
overrides always emit an explicit `dispatch = DispatchMode.<MODE>` because
`recordNow`'s runtime default is `ASYNC`, not the engine configuration; the
annotation processor still rejects `@Log(dispatch = INLINE)` on non-suspend
functions (use `recordBlocking` manually if synchronous delivery is required).

## The failure payload

`LogFailure.from(error)` runs **eagerly at event creation**, so the structured
payload stays correct even if the exception mutates later:

| Field | Content |
|---|---|
| `errorType` | fully-qualified exception class name |
| `message` | `error.message` (nullable) |
| `rootCauseType` | deepest non-self-referential `cause` class name |
| `stackSummary` | first `LogFailure.DEFAULT_STACK_FRAMES = 8` frames, class-level info only |

No raw throwable ever travels inside the event; sinks see only this
immutable description. Full instrumentation-approach trade-offs:
[27-manual-instrumentation.md](27-manual-instrumentation.md).

## Event anatomy (`LogEvent`)

```
id               string   unique (UuidEventIdGenerator by default; injectable)
timestampMillis  long     injectable clock
level            enum     TRACE..AUDIT
action           string   e.g. task.created
schemaId         string   "task.created" or "task.created@v2"
outcome          enum     START / SUCCESS / FAILURE
entity, entityId string?  proven at compile time
actor            Actor?   (type, id, name, attributes)
correlationId    string?  from ambient context
causationId      string?  from ambient context
source           string   e.g. "TaskRepository.insert" (interface signature)
category         string?  routing key
metadata         Map      exactly the selected values
changes          List     FieldChange(field, before, after)
failure          LogFailure?  structured failure payload
```

## Worked example

From `examples/server` (Ktor): one POST creates an order:

```
[AUDIT] order.created actor=u-9 Order(2) via OrderRepository.create corr=client-req-42 {title=flat white}
[AUDIT] http.request actor=u-9 HttpRequest(client-req-42) via ktor.server {method=POST, path=/orders, status=200, durationMillis=3}
```

Both events share `corr=client-req-42` because the plugin establishes the
ambient context per request ([20-ktor-integration.md](20-ktor-integration.md)).
