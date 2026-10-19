# 05. Event lifecycle

Every `@Log` function emits **events in a fixed order**. The wrapper calls the
delegate first and only *then* reports success — success events never lie.

## Outcomes

| Outcome | Meaning | When recorded |
|---|---|---|
| `START` | operation was *about to* run | only with `before = true` |
| `SUCCESS` | operation completed normally | default (`onSuccess = true`) |
| `FAILURE` | operation threw | with `onFailure = true` |

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
- Changes (`LogChange`) are recorded **on `SUCCESS` only** — a "before→after"
  pair cannot be trusted mid-crash.

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
