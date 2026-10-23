# 02. Quickstart

## The five-minute path

A complete, audited operation in five steps. The numbered sections below
explain each one; [40-cheatsheet.md](40-cheatsheet.md) condenses the whole
surface onto a single page.

```kotlin
// build.gradle.kts — the plugin adds KSP, the runtime, annotations and processor
plugins {
    id("io.github.mohamed-dev-ui.ilogger") version "2.0.0"
}
ilogger { strict.set(true) }   // fail the build on instrumentation mistakes
```

```kotlin
// 1. One constant per action — the audit vocabulary.
object TaskActions {
    const val CREATED = "task.created"
}

// 2. Annotate the interface, not the implementation.
interface TaskRepository {
    @Log(action = TaskActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: Task): Long
}

// 3. Implement it normally — no logging code.
class InMemoryTaskRepository : TaskRepository {
    private var nextId = 0L
    override suspend fun insert(task: Task): Long = ++nextId
}

// 4. Create the engine once, attach the generated adapter.
val logger = ILogger.create { sink(ConsoleLogSink()) }
val dao = InMemoryTaskRepository().logged(logger)

// 5. Call the operation. The actor comes from the ambient scope.
withActor(Actor.user("u-42", name = "Grace")) {
    dao.insert(Task(title = "ship it"))
}
```

One console line appears, and the same event reaches every other sink
(Room, file, HTTP) without touching `insert`:

```
1783212345678 [AUDIT] task.created actor=u-42(Grace) Task(1) via TaskRepository.insert {title=ship it}
```

`entity=Task` and `entityId=1` were inferred from the `Task` parameter and
the `Long` return value; the source is the interface signature. The adapter
still returns the row id and an exception still propagates unchanged.

The plugin marker lives on Maven Central — if resolution fails, check the
`pluginManagement` repositories in [03](03-ksp-setup.md). That is the 90%
case. Next: [18](18-testing.md) proves it in a test,
[13](13-categories-and-routing.md) routes by category, [14](14-sinks.md)
lists durable sinks, and [26](26-configuration-reference.md) covers the
engine knobs. Adopting an existing codebase? Start at [39](39-migration.md).

## 1. Dependencies

One plugin declaration wires KSP, the runtime, the annotations and the
processor at the same version:

```toml
# gradle/libs.versions.toml
[versions]
ilogger = "2.0.0"

[plugins]
ilogger = { id = "io.github.mohamed-dev-ui.ilogger", version.ref = "ilogger" }
```

```kotlin
// module build.gradle.kts
plugins {
    alias(libs.plugins.ilogger)
}

ilogger {
    strict.set(true)   // recommended for application modules
}
```

The marker artifact lives on Maven Central, so make sure the root
`settings.gradle.kts` can see it:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

The plugin works for JVM and Android modules. For KMP or full manual control,
wire KSP and the coordinates yourself:

<details>
<summary>Manual setup</summary>

```toml
[versions]
kotlin = "2.2.10"
ksp = "2.3.12"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }

[libraries]
ilogger-annotations = { group = "io.github.mohamed-dev-ui", name = "ilogger-annotations", version = "2.0.0" }
ilogger-core = { group = "io.github.mohamed-dev-ui", name = "ilogger-core", version = "2.0.0" }
ilogger-ksp = { group = "io.github.mohamed-dev-ui", name = "ilogger-ksp", version = "2.0.0" }
```

```kotlin
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

</details>

Use `platform("io.github.mohamed-dev-ui:ilogger-bom:2.0.0")` to align the
optional modules (`testing`, `room`, `serialization`, `ktor`) without repeating
versions. Setup details and processor options: [03-ksp-setup.md](03-ksp-setup.md).

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
