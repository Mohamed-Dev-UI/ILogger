# 11. Actors & ambient context

An **actor** answers "who performed this operation". ILogger separates the
actor *value* from its *ambient propagation*.

## Actor model

```kotlin
Actor.user(id, name = "Grace", attributes = mapOf("tenant" to "acme"))
```

- `type` is part of the actor (`user`, `service`, ...) — pruned from `attributes`.
- `id` is required non-blank for `user` — attribution without an id is a bug,
  so it fails fast.
- Actor fields are compile-time accessible as source expressions: `actor`,
  `actor.name`, `actor.type`, `actor.<attribute key>`.

## Two propagation paths (choose explicitly)

| Path | API | Sees what |
|---|---|---|
| suspend scopes | `withActor(Actor.user(...)) { ... }` | coroutine-scoped; **the** default for application code |
| non-suspend bridge | `LogContexts.onCurrentThread(context) { ... }` | thread-local only — for callbacks, scheduled jobs, `recordNow` paths |

Both are nestable and merge predictably: inner `withActor` overrides the
actor while keeping correlation, `attributes` accumulate, etc. (full merge
rules in `LogContext.plus`).

## Nesting

```kotlin
withActor(Actor.user("u-42", name = "Grace")) {
    withCorrelation("rpc-77") {              // keeps u-42, adds correlation
        withContextAttributes("tenant" to "acme") {
            dao.insert(task)                 // event: actor+corr+attributes
        }
    }
}
```

Console evidence (`examples/advanced-audit`):

```
[AUDIT] effect.name actor=u-7(Ada) via example.advanced corr=flow-42
```

## Application-provided actors

For hosts that resolve actors globally (thread pools, schedulers), inject
`actorProvider` into the builder:

```kotlin
ILogger.create {
    actorProvider = ActorProvider { mdcActor() }
}
```

Events without an explicit ambient value consult the provider — but the
ambient coroutine context always wins; the provider is a fallback.

## Caveat: framework-bridge

The suspend propagation is *state-less per coroutine*: a `ThreadLocal`-based
MDC file/global variable can race. Prefer passing actors through the
framework wrappers (e.g. Ktor's `withAuditContext`) — they build a proper
`LogContext` per request ([20-ktor-integration.md](20-ktor-integration.md)).
