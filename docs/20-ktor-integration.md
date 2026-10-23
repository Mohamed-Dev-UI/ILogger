# 20. Ktor integration (`ilogger-ktor`)

Two plugins share one event vocabulary:

| Plugin | Side | Action | Purpose |
|---|---|---|---|
| `HttpAudit` | server | `http.request` | One audit event per request + request-scoped ambient context |
| `HttpAuditClient` | client | `http.client.request` | One audit event per outbound send + correlation/`traceparent` propagation |

Domain wrappers inherit the request context, so one client call and the
resulting server work share a correlation id end to end.

```kotlin
implementation("io.github.mohamed-dev-ui:ilogger-ktor:<version>")
```

The artifact exposes `ktor-server-core` and `ktor-client-core` as API
dependencies; add the engine you use (`ktor-server-cio`, `ktor-client-cio`,
...) and `ktor-client-content-negotiation` separately.

## Install (server)

```kotlin
val logger = ILogger.create { sink(ConsoleLogSink()) }

embeddedServer(CIO, host = "127.0.0.1", port = 0) {
    install(HttpAudit) {
        logger = auditLogger
        // actorExtractor: ApplicationCall -> Actor? (default: X-User-Id → Actor.user)
        // correlationHeader: "X-Request-Id"
        // excludePaths: health/probe prefixes, never recorded
        // requestFilter: { call -> ... }        extra per-call filter
        // sampling = 1.0                        per-call recording rate
        // failureEvents = HttpFailureEvents.SINGLE
    }
    routing { ... }
}
```

## Install (client)

```kotlin
val client = HttpClient(CIO) {
    install(HttpAuditClient) {
        logger = auditLogger
        // correlationHeader: "X-Request-Id"
        // traceparentHeader: "traceparent"
        // generateTraceparent = false           mint when no context exists
        // sampling = 1.0                        per-call recording rate
        // failureLevel: ERROR                   transport failures only
    }
}
```

The client plugin is outbound-only and does not depend on the server plugin.

## What the server records

One `http.request` event per call:

```
[AUDIT] http.request actor=u-9 HttpRequest(41aafa8b...) via ktor.server
        {method=POST, path=/orders, status=200, durationMillis=145}
```

- `entityId` = correlation id (from `X-Request-Id` or a generated UUID echoed
  in the response header for tracing at the edge).
- `category = "http"` by default; 4xx/5xx → `outcome = FAILURE`, the rest
  `SUCCESS`.
- Exceptions surface through `on(CallFailed)` with the structured failure
  payload attached. The failure event carries `status = 500` when the engine
  has not produced a status yet.
- Health/probe prefixes are skipped (`excludePaths`, default
  `/health`, `/ready`, `/metrics`); `requestFilter` adds arbitrary per-call
  exclusion. Correlation ids are still established for excluded calls.
- Durations use the injected `clock`; swap it with a `FixedClock` in tests.

### One event per request

`ResponseSent` and `CallFailed` can both fire for the same failing call,
depending on the engine. The default `HttpFailureEvents.SINGLE` records only
the first event and suppresses the second, so success and failure paths both
produce exactly one row. `HttpFailureEvents.BOTH` keeps both: the handler
failure with its cause, then the response status. Dual mode is useful when
consumers need the wire status as a separate event.

`ResponseSent` is a non-suspend engine hook, so its event is handed to the
application scope without blocking the response (`ILogger.record`, not the
thread-local `recordNow` bridge). Under immediate process shutdown, flush the
logger; a buffered event that has not started recording is best-effort.

### Sampling and filtering

`sampling` is a per-call rate (`0.0..1.0`), decided once at call setup and
applied to the failure event(s) as well. It filters *recording*, not
correlation: unsampled calls still get a correlation id and echo header.
Core sampling (`ILoggerBuilder.sampling`, `auditSampling`) still applies
afterwards.

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
scopes: inner scopes keep their winning rules. Without the plugin installed,
the block runs with your `actor`/tags but no correlation id (the fresh-UUID
path exists only when the plugin's `CallSetup` hook runs and no header came in).

## What the client records

One `http.client.request` event per send attempt:

```
[AUDIT] http.client.request actor=u-9 HttpRequest(req-42) via ktor.client
        {method=GET, host=inventory.example, path=/stock, status=200, durationMillis=12}
```

| Field | Value |
|---|---|
| `entityId` | effective correlation id (existing header, ambient `LogContext.correlationId`, else `null`) |
| `actor` | `HttpAuditClientConfig.actor`, else ambient actor |
| `outcome` | `FAILURE` for HTTP ≥ 400 and for transport exceptions; otherwise `SUCCESS` |
| `level` | `level` (AUDIT) for responses, `failureLevel` (ERROR) for transport exceptions |
| `metadata` | `method`, `host`, `path` (no query), `status` (responses only), `durationMillis`, `transport = true` (exceptions only) |
| `failure` | structured payload for transport exceptions; the original exception is rethrown |
| `category` | `"http"` by default |

### Correlation and `traceparent` propagation

Header injection never overwrites a value already on the request:

| Header | Resolution order |
|---|---|
| `X-Request-Id` (`correlationHeader`) | existing request header → ambient `LogContext.correlationId` → absent |
| `traceparent` | existing request header → `LogContext.attributes["traceparent"]` → generated when `generateTraceparent = true` |

```kotlin
withCorrelation("checkout-42") {
    withContextAttributes("traceparent" to trace) {
        client.get("https://inventory.example/stock")   // both headers set
    }
}
```

Generated traceparents follow the W3C format
(`00-<32 hex trace id>-<16 hex span id>-01`) but are *not* backed by a tracer
span; `generateTraceparent` is off by default so distributed tracing systems
that already own trace ids stay authoritative.

`sampling` only suppresses the audit event; correlation and trace headers are
still propagated. `requestFilter` is a harder opt-out: no event and no header
injection.

## Correlation across client and server

Install both plugins with the same logger (or two loggers sharing a sink):

```kotlin
withCorrelation("edge-1") {
    client.get("https://api.example/orders")   // client event + X-Request-Id
}
// server event for the same call carries entityId = "edge-1"
```

This is covered by `client correlation is visible to the server plugin` in
`HttpAuditClientTest`.

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

| Plugin | Hook | action | level | outcome | entityId | metadata | failure |
|---|---|---|---|---|---|---|---|
| server | `ResponseSent` | `http.request` | `AUDIT` | `FAILURE` if status ≥ 400 else `SUCCESS` | correlation id | `method`, `path`, `status`, `durationMillis` | — |
| server | `CallFailed` | `http.request` | `ERROR` | `FAILURE` | correlation id | `method`, `path`, `status` (500 unless set) | thrown cause |
| client | send completes | `http.client.request` | `AUDIT` | `FAILURE` if status ≥ 400 else `SUCCESS` | correlation id | `method`, `host`, `path`, `status`, `durationMillis` | — |
| client | send throws | `http.client.request` | `ERROR` | `FAILURE` | correlation id | `method`, `host`, `path`, `durationMillis`, `transport = true` | thrown cause |

Notes: both plugins set `category = "http"`; durations come from the injected
`LogClock`; `path` never includes the query string. Redirects and retry
attempts are separate sends, so the client plugin can emit one event per
attempt.
