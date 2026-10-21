# 20. Ktor integration (`ilogger-ktor`)

The `HttpAudit` plugin turns every request into audit context + one envelope
event; domain wrappers inherit both — end-to-end attribution in one install.

## Install

```kotlin
val logger = ILogger.create { sink(ConsoleLogSink()) }

embeddedServer(CIO, host = "127.0.0.1", port = 0) {
    install(HttpAudit) {
        logger = auditLogger
        // actorExtractor: ApplicationCall -> Actor? (default: X-User-Id → Actor.user)
        // correlationHeader: "X-Request-Id"
        // excludePaths: health/probe prefixes, never recorded
    }
    routing { ... }
}
```

## What it records

One `http.request` event per call:

```
[AUDIT] http.request actor=u-9 HttpRequest(41aafa8b...) via ktor.server
        {method=POST, path=/orders, status=200, durationMillis=145}
```

- `entityId` = correlation id (from `X-Request-Id` header or a generated UUID
  echoed in the response header for tracing at the edge).
- 4xx/5xx → `outcome = FAILURE`; the rest `SUCCESS`.
- Exceptions surface through `on(CallFailed)` with the structured failure
  payload attached — the 500 response also gets its row: nothing is lost
  between hooks.
- Health/probe prefixes are skipped (`excludePaths`, default
  `/health`, `/ready`, `/metrics`).

## Request-scoped ambient context in handlers

```kotlin
post("/orders") {
    val title = call.receiveText()
    call.withAuditContext(
        actor = call.request.headers["X-User-Id"]?.let { Actor.user(it) },
        "tenant" to "toronto",
    ) {
        repository.create(title)   // generated wrapper: audit event inherits all
        call.respondText("order created")
    }
}
```

Evidence (`examples/server`):

```
[AUDIT] order.created actor=u-9 Order(2) via OrderRepository.create corr=client-req-42 {title=flat white}
[AUDIT] http.request  actor=u-9 HttpRequest(client-req-42) via ktor.server {...}
```

`withAuditContext` merges like the core `withActor`/`withCorrelation`
scopes: inner scopes keep winning rules; correlate with your own ids too by
picking `actor`/tags per call. Without the plugin installed, the block runs
with your `actor`/tags but **no correlation id** (the fresh-UUID path exists
only when the plugin's `CallSetup` hook runs and no `X-Request-Id` came in).

## Authenticated actors (production guidance)

Headers are **unauthenticated input**. Resolve actors from principals in real
apps:

```kotlin
install(HttpAudit) {
    logger = auditLogger
    actorExtractor = { call ->
        call.principal<AuthUser>()?.let { Actor.user(it.id, name = it.name) }
    }
}
```

Actor-extraction options compared:

| Option | Source | Trust level | Cost | Verdict |
|---|---|---|---|---|
| `call.principal<AuthUser>()` | Auth middleware/session | Authenticated | Principal lookup | **🏆 Recommended** for real auth |
| `X-User-Id` header (default) | Client-supplied header | Untrusted | Zero | Prototyping/internal nets only — validate or drop before logging |
| Service-token mapping | API key → `Actor.service(id)` | Semi-trusted | One lookup | Machine callers (document the mapping) |

Header extraction is the default only because it is zero-config; treat it
exactly like an untrusted logging field.

## Events reference

| Hook | action | level | outcome | entityId | metadata | failure |
|---|---|---|---|---|---|---|
| `ResponseSent` | `http.request` | `AUDIT` | `FAILURE` if status ≥ 400 else `SUCCESS` | correlation id | `method`, `path` (no query), `status`, `durationMillis` | — |
| `CallFailed` | `http.request` | `ERROR` | `FAILURE` | correlation id | `method`, `path` | thrown cause (structured `LogFailure`) |

Notes: no `category` is set on these events (they always reach the
catch-all); durations are wall-clock (`System.currentTimeMillis`) regardless
of the logger's `LogClock`; `excludePaths` is consulted at response/failure
time (prefix match, no query), while a correlation id is still established
for excluded calls.
