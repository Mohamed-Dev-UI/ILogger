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
picking `actor`/tags per call.

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

Header extraction is the default only because it is zero-config; treat it
exactly like an untrusted logging field.
