# 11. Actors & ambient context

An **actor** answers "who performed this operation". ILogger separates the
actor *value* from its *ambient propagation*.

## Actor model

```kotlin
Actor.user(id, name = "Grace", attributes = mapOf("tenant" to "acme"))
```

## The full Actor surface

```kotlin
Actor.user("u-42", name = "Grace", attributes = mapOf("tenant" to "acme"))
```

- `type` is part of the actor (`user`, `service`, ...) — pruned from `attributes`.
- `id` is required non-blank for `user` — attribution without an id is a bug,
  so it fails fast ("Actor id must not be blank").
- Actor fields are compile-time accessible as source expressions: `actor`,
  `actor.name`, `actor.type`, `actor.<attribute key>`.

Every `Actor` is immutable and value-equal. Build them from companions:

| Factory | `id` | Use for |
|---|---|---|
| `Actor.user(id, name?, attributes)` | **required, non-blank** | Authenticated humans |
| `Actor.system(attributes)` | null | The application itself |
| `Actor.worker(id?, name?, attributes)` | optional | Scheduled/background jobs |
| `Actor.automation(id?, ...)` | optional | Triggers, cron, IFTTT-style flows |
| `Actor.plugin(id?, ...)` | optional | Extensions acting inside the app |
| `Actor.service(id?, ...)` | optional | Inter-service callers (API keys, service accounts) |
| `Actor.anonymous()` | null | Unauthenticated callers — records that *someone* acted |
| `Actor.custom(id?, name?, attributes)` | optional | Any identity system that doesn't fit |

`ActorType` (coarse categories the identity system maps onto): `USER`,
`SYSTEM`, `WORKER`, `AUTOMATION`, `PLUGIN`, `SERVICE`, `ANONYMOUS`, `CUSTOM`.

Choosing a type is worth a moment: `type` survives into every event and is a
cheap query dimension (`actor.type.name` when selected via `LogMeta("actor.type")`).

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
ambient coroutine context always wins; the provider is a fallback. Full actor
resolution order: **explicit parameter → ambient `LogContext.actor` →
configured `actorProvider`**.

## Attributes and vocabulary

```kotlin
withContextAttributes("tenant" to "acme", "sessionId" to "s-9") { ... }
```

`LogContext.attributes` is free-form `String → String` — your vocabulary.
Values selected in events via `context.<key>` expressions are copied into the
event's metadata under that key (e.g. `{tenantId=acme}` when a `@Log` selects
`LogMeta("context.tenantId")`). Keep the vocabulary small and stable — these
keys are as public as action codes.

## Suppressing duplicate sources (wrapped operations)

When an operation is instrumented at two layers (a repository *and* its DAO),
you get two events for one logical action. Name one layer and suppress it:

```kotlin
withLogContext(LogContext(suppressSources = setOf("TaskDao.insert"))) { ... }
```

`LogEvent.source` values listed here are matched **exactly** (not prefix
matching) and the matching event is dropped before allocation — zero cost of
the suppressed event. The layer that stays visible records once, with the
inner layer's enriched entity id if annotated there.

Alternatives for the same problem:

| Approach | Behavior | Recommended when |
|---|---|---|
| `suppressSources` in ambient context | Drop at event build; caller-scoped | **🏆 Recommended** — scoped, reversible, no code changes |
| Annotate only one layer | Other layer simply has no `@Log` | Permanent single-source design |
| Route by `category` into different sinks | Both events recorded, separated per sink | When you need both layers for different audiences |

## Caveat: framework-bridge

The suspend propagation is *state-less per coroutine*: a `ThreadLocal`-based
MDC file/global variable can race. Prefer passing actors through the
framework wrappers (e.g. Ktor's `withAuditContext`) — they build a proper
`LogContext` per request ([20-ktor-integration.md](20-ktor-integration.md)).
