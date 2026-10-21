# 12. Correlation & causation

Audits become investigations when events link into stories.

## Correlation — the story id

All events in one *logical operation* share a `correlationId`. Typical
operation stories: one HTTP request, one WebSocket session, one queue message,
one scheduled run.

```kotlin
withCorrelation("req-abc") {
    dao.insert(task)
    billing.charge(amount)   // another @Log wrapper, same correlation
}
```

Both events print `corr=req-abc`; later queries (`WHERE schemaId =
'task.created' AND correlationId = 'req-abc'`) reconstruct the story.

### Who generates the correlation id — three options

| Option | Example | Pros | Cons | Verdict |
|---|---|---|---|---|
| Incoming header (`X-Request-Id`, Ktor default) | echo and reuse the caller's value | Links client and server traces natively | Untrusted input — must be validated/sanitized | **🏆 Recommended** for server front-ends (with hygiene) |
| Server-generated UUID | `UUID.randomUUID()` per call | No trusting the caller; always present | Client↔server trace correlation needs the echo response header | Fallback/default; Ktor plugin does this automatically |
| Domain-supplied id (order no., session id) | `withCorrelation("order-1042")` | Business-meaningful: all events on one order link | Id reuse across runs can over-correlate | Best *inside* a bounded context with unique ids |

**Recommended flow:** incoming header when present, UUID fallback otherwise
(the `HttpAudit` plugin implements exactly this — see
[20-ktor-integration.md](20-ktor-integration.md)), and a domain id when the
"story" is a business object rather than a request.

## Causation — which event caused this

`causationId` points at the *event id* that caused the current event. Chain:

```kotlin
withCausation(priorEventId) {
    dao.retry(orderId)
}
```

Practical scheme: the Ktor plugin puts the **request's correlation id** on
the envelope (`entityId` of `http.request`); domain wrappers inherit the same
value as their `correlationId`. When a request schedules later work, pass
`withCausation(correlationIdOfTheRequest)` into the async job: the audit
trail records "this retry exists because that request happened".

## Ids are injectable & deterministic

| Generator | Use |
|---|---|
| `UuidEventIdGenerator` (default) | production |
| `SequentialIdGenerator` (`ilogger-testing`) | fixture mode: `e1, e2, ...` comparable in tests |

The clock is injectable too (`FixedClock` in testing) — tests assert exact
timestamps and chain shapes without sleeps, jitter or flakes.

## Contract details

- Correlation/causation are **strings** in `LogContext`, not magic objects.
- They live in the ambient context — `withCorrelation`/`withCausation` scope
  helpers AND can merge with `withActor` at any depth
  ([11-actors-and-ambient-context.md](11-actors-and-ambient-context.md)).
- The `http.request` event stores the correlation id as its *entityId* as
  well: asking "what happened to request X" finds the envelope immediately.
