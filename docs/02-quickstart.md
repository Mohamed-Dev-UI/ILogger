# 02. Quickstart

## 1. Dependencies

Set your `libs.versions.toml` (or direct coordinates):

```toml
[versions]
kotlin = "2.2.10"
ksp = "2.3.12"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }

[libraries]
ilogger-annotations = { group = "io.github.mohamed-dev-ui", name = "ilogger-annotations", version = "0.1.0" }
ilogger-core = { group = "io.github.mohamed-dev-ui", name = "ilogger-core", version = "0.1.0" }
ilogger-ksp = { group = "io.github.mohamed-dev-ui", name = "ilogger-ksp", version = "0.1.0" }
```

```kotlin
// module build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(libs.ilogger.annotations)
    implementation(libs.ilogger.core)
    ksp(libs.ilogger.ksp)
}
```

## 2. Actions as constants

```kotlin
object TaskActions {
    const val CREATED = "task.created"
    const val CLOSED = "task.closed"
}
```

## 3. Annotate the interface

```kotlin
interface TaskRepository {

    @Log(action = TaskActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: Task): Long

    @Log(
        action = TaskActions.CLOSED,
        category = "workflows",
        metadata = [LogMeta("task.id"), LogMeta("'closure'", name = "event")],
    )
    suspend fun close(taskId: Long)
}
```

## 4. Wrap and use

```kotlin
val logger = ILogger.create {
    sink(ConsoleLogSink())          // human console during development
    dispatch = DispatchMode.ASYNC   // engine-wide async pipeline (default)
}

val dao = InMemoryTaskRepository().logged(logger)   // <- generated adapter

withActor(Actor.user("u-42", name = "Grace")) {
    dao.insert(Task(title = "ship it"))   // suspend: ambient actor attached
}
```

Console output (one line per event):

```
1783212345678 [AUDIT] task.created actor=u-42(Grace) Task(1) via TaskRepository.insert {title=ship it}
```

## 5. What you got without writing anything

- `entity`/`entityId` inferred: `Task`, id = the insert **result** (row id 1).
- actor: inherited from the ambient `withActor` scope.
- source: `TaskRepository.insert` (uses the interface signature — stable across
  implementations).
- metadata: exactly `task.title` — the domain object is never serialized
  wholesale.

## 6. Verify behavior in tests

```kotlin
val kit = TestKit.create()
val dao = InMemoryTaskRepository().logged(kit.logger)

runBlocking { dao.insert(Task(title = "x")) }

kit.sink.awaitCount(1)
val event = kit.sink.events.single()
assertEquals("task.created", event.action)
assertEquals("3", event.entityId)
```

([18-testing.md](18-testing.md))

## Alternative wirings (same events, different ownership)

| Wiring | When | Cost | Verdict |
|---|---|---|---|
| Explicit instance (`ILogger.create`) | Everything with a defined lifecycle | ~4 lines | **🏆 Recommended** (also see [26](26-configuration-reference.md)) |
| `ILogger.configure { }` + `ILogger.default` | Scripts/prototypes; libraries that adopt the host's default | 1 line | Global state; replaced instances are never auto-closed |
| DI-owned instance (Hilt `@Provides`) | Android/complex graphs | Module recipes in [21](21-android-app-guide.md) | Same engine semantics, graph-scoped lifecycle |
| `ILogger.disabled` | Deliberate opt-out (flavor builds) | 0 | Drops everything by contract |

The quickstart above uses the explicit instance: one object owning its
sinks and a deterministic `close()`. Everything else (categories, room
persistence, Ktor context) composes onto it without new machinery.
