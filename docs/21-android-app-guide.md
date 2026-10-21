# 21. Android reference app (`:app`)

The `:app` module is the integration reference for ILogger on Android. It is a
single Compose app that exercises **every Android-facing library surface** —
annotation-driven repositories, a `@LogDao` Room DAO, actors and ambient
context, correlation/causation, sensitive-value policies, category routing,
Room and JSONL sinks, retention, dispatch/backpressure policies, sampling,
manual instrumentation and TestKit — against the real engine, not mocks.

One app is enough: each concern is a screen, and every screen drives the same
application-scoped logger. The only server-only integration is Ktor; it is
covered by [`examples/server`](../examples/server) and the Recipes screen.

## What every screen proves

| Screen | What it exercises |
|---|---|
| **Overview** | Live metrics from an `onEvents` trigger, top actions, categories, the exact logger profile |
| **Task board** | `@Log` repository + `@LogDao` DAO, six actor types, tenant attributes, correlation, `before`, `onFailure`, `LogChange`, extractors, `suppressSources`, manual `record`, Flow collection, `recordNow` + `LogContexts.onCurrentThread`, diagnostics, failure propagation and causation |
| **Live events** | The catch-all in-memory sink: filtering by level/outcome/category/text, `onDispatchError` issues, full typed event details and lossless JSON |
| **Room audit** | `RoomLogTarget`, library-owned `ilogger_events` table, queued `room.database.created/opened` lifecycle events, retention sweeps, a second connection in the device test |
| **JSONL files** | `TextFileSink` + `JsonEventCodec`, audit-only category routing, size-based rotation, read-back of the real file |
| **Protection** | `@LogSensitive` contract, HASH/MASK on the same value, raw-value leak check, `@LogExclude` and the compile-time refusal list |
| **Engine lab** | Bounded buffer, DROP_OLDEST vs BLOCK, RETRY, INLINE, PROPAGATE, sampling, `minLevel` and the runtime `enabled` switch — each run against an independent logger |
| **TestKit** | The same deterministic suite as `BenchSuiteTest`, run on-device with `FixedClock` and sequential ids |
| **Recipes** | Copy-ready Gradle, Hilt, Room, Koin, Ktor, sink, testing and diagnostics snippets |

The source is deliberately small and screen-oriented:

```
app/src/main/java/com/mohamed/dev/ui/ilogger/
├── logging/           # app-owned domain, session and logger graph
│   ├── Domain.kt          Task + TaskDao (@LogDao) + extractors
│   ├── TaskRepository.kt  annotated contract, cache and Flow boundary
│   ├── DemoSession.kt     actor/tenant/correlation/suppression scope
│   ├── LoggingModule.kt   Hilt graph: target → logger → database → wrappers
│   ├── EventBuffer.kt     bounded live feed + dispatch issues
│   ├── AuditMetrics.kt    onEvents aggregation
│   └── AuditFileStore.kt  read side of the JSONL sink
└── ui/                # app-scoped controllers + Compose screens
```

## Hilt wiring

The graph extends the recommended Android shape with category routing and
lifecycle telemetry. The important ordering is `RoomLogTarget -> ILogger ->
AppDatabase`, so `room.database.created` and `room.database.opened` are
recorded before the DAO exists, then drained by `bind`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides @Singleton
    fun target(feed: EventBuffer): RoomLogTarget = RoomLogTarget(
        retention = RoomLogRetention.keepDays(days = 30),
        onError = { error -> feed.reportRetentionError(error) },
    )

    @Provides @Singleton
    fun logger(
        feed: EventBuffer,
        metrics: AuditMetrics,
        target: RoomLogTarget,
        files: AuditFileStore,
    ): ILogger = ILogger.create {
        dispatch = DispatchMode.BUFFERED
        bufferSize = 512
        batchSize = 32
        flushIntervalMillis = 150
        overflow = OverflowPolicy.DROP_OLDEST
        closeTimeoutMillis = 3_000
        onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)

        sink(feed.sink)                                       // catch-all live view
        sink(target)                                          // catch-all durable trail
        sink(ConsoleLogSink { line -> Log.d("ILogger", line) },
             category = DIAGNOSTIC_CATEGORY)                  // Logcat, low volume
        sink(TextFileSink(
            file = files.file.toPath(),
            codec = JsonEventCodec,
            append = true,
            flushEveryWrite = true,
            rotation = TextFileSink.Rotation(maxBytesPerFile = 12 * 1024, maxFiles = 3),
        ), category = AUDIT_CATEGORY)                         // strict forensics
        onEvents { events -> metrics.record(events) }         // failure-isolated UI metrics
        onDispatchError = { event, sink, error -> feed.reportDispatchError(event, sink, error) }
    }

    @Provides @Singleton
    fun database(
        @ApplicationContext context: Context,
        logger: ILogger,
        target: RoomLogTarget,
    ): AppDatabase = Room.databaseBuilder<AppDatabase>(context, DATABASE_NAME)
        .withAuditCallback(logger, label = DATABASE_NAME)
        .build()
        .also { target.bind(it.logDao()) }

    @Provides @Singleton
    fun taskDao(db: AppDatabase, logger: ILogger): TaskDao = db.logged(logger).taskDao()

    @Provides @Singleton
    fun taskRepository(impl: RoomTaskRepository, logger: ILogger): TaskRepository =
        impl.logged(logger)                                   // generated wrapper, injected once
}
```

`AppDatabase` owns the schema and the migration policy; ILogger only supplies
`RoomLogEntity` and `RoomLogDao`. The demo uses
`fallbackToDestructiveMigration` because it is a sample app — a production app
adds a normal migration (`RoomLogSchema.migration(...)` is available).

The activity injects only app-scoped controllers. An in-flight audited
operation therefore survives rotation and navigation; no screen owns a logger,
a database or a coroutine that can be cancelled by recomposition.

## Session, actor and correlation

`DemoSession` is the app-owned ambient scope. Every UI action runs through it:

```kotlin
suspend fun <R> run(block: suspend CoroutineScope.() -> R): R =
    withLogContext(
        LogContext(
            actor = cast.actor(),
            correlationId = "op-${counter.incrementAndGet()}",
            attributes = mapOf("tenantId" to tenant, "sessionId" to "android-demo"),
        ),
        block,
    )
```

It demonstrates all six actor factories (`user`, `system`, `worker`,
`automation`, `service`, `anonymous`), `withContextAttributes`-style tenant
data, one correlation id per logical action, and `context.tenantId` selection
in `@Log` metadata.

`suppressSources` is wired as a switch so the duplicate-layer pattern is
visible: with it on, `TaskDao.*` events are dropped before allocation while
the `TaskRepository` event stays; with it off both layers appear.

The non-suspend `SessionCache` path uses the thread-local bridge instead:

```kotlin
withContext(Dispatchers.Default) {
    LogContexts.onCurrentThread(session.threadContext()) {
        cache.put(task)          // generated recordNow path
    }
}
```

This is the correct split: generated suspend functions read the coroutine
context; `recordNow` reads only the bridge, never the coroutine context.

## The event pipeline

All screens share the one `BUFFERED` logger. The live feed is a sink with a
hard cap (`EventBuffer.MAX_EVENTS = 400`); the durable path is Room; the JSONL
file sees only `category = "audit"`; Logcat sees only
`category = "diagnostics"`. The `onEvents` trigger updates the dashboard off
the producer path, and `onDispatchError` turns overflow, closed-logger drops,
retries-exhausted and recursive dispatch into visible rows instead of silence.

The **Engine lab** is the deliberate exception: each experiment creates an
independent logger, measures it and closes it, so changing `overflow`,
`enabled` or error modes never mutates the app's production-shaped instance.

## Annotated surfaces in the demo

`Domain.kt` shows the three common shapes:

- a repository contract annotated directly (`TaskRepository`) with result-derived
  entity ids, `before`, `onFailure`, `schemaVersion`, `category`, an explicit
  `actor` override, value expressions (`context.tenantId`, `actor.name`,
  `'ARCHIVED'`), extractors and a manual `record` at the search boundary;
- a Room DAO annotated with `@LogDao`, wrapped once by
  `db.logged(logger).taskDao()`;
- a synchronous cache whose generated `recordNow` path is bridged explicitly.

DI variants: `@LogDao(hilt = true)` removes even the manual
`db.logged(logger)` line. It is demonstrated by
[`HiltDaoBindingsTest`](../app/src/androidTest/java/com/mohamed/dev/ui/ilogger/logging/HiltDaoBindingsTest.kt);
the generated Koin module is covered by
[`KoinDaoBindingsTest`](../app/src/androidTest/java/com/mohamed/dev/ui/ilogger/logging/KoinDaoBindingsTest.kt).
You do not need multiple app modules to adopt either DI style.

## Run it

```bash
./gradlew :app:installDebug
adb shell am start -n com.mohamed.dev.ui.ilogger/.MainActivity

# JVM check: the same TestKit suite the in-app screen runs
./gradlew :app:testDebugUnitTest

# Device tests: real Hilt graph + real Room file + generated bindings
./gradlew :app:connectedDebugAndroidTest
```

The three instrumented tests are the proof that the wiring is real:

| Test | Proves |
|---|---|
| `RoomLoggingInstrumentedTest` | The injected repository writes through the real logger into the on-device Room file; a second `Room.databaseBuilder` connection sees the row; the injected non-suspend `SessionCache` proves `recordNow` is wrapped in DI |
| `HiltDaoBindingsTest` | `@LogDao(hilt = true)` generates a module that injects an already-wrapped DAO, no `db.logged(...)` at the call site |
| `KoinDaoBindingsTest` | `@LogDao(koin = true)` returns the wrapped DAO from a real Koin container |

Hilt rejects tests that run under `@HiltAndroidApp`, so
[`ILoggerTestRunner`](../app/src/androidTest/java/com/mohamed/dev/ui/ilogger/logging/ILoggerTestRunner.kt)
boots `HiltTestApplication`; Hilt still aggregates the production modules, so
the injected logger/database/repository are the app's real singletons.

## Production notes

- Flush on `Application.onTrimMemory` (the demo does). Use `flush()`/`close()`
  only from the owner; the logger is a singleton, not a per-screen object.
- `BUFFERED` + `DROP_OLDEST` is the mobile default: bounded memory and
  recency under a flood. Use `BLOCK` only off the main thread, and
  `INLINE`/`PROPAGATE` only when missing the audit is worse than failing the
  operation.
- Keep `@Log` on the interface and wrap the implementation once in DI. Never
  wrap inside a screen, view model or repository method.
- Keep audit and JSONL on their own categories; do not let diagnostic noise
  bloat the forensic file.
- Display sensitive values only on explicit user action, even in a demo. The
  Protection screen hides the raw note behind a reveal control and verifies the
  emitted event contains only the hash and mask.

## What NOT to do on Android

- Do not block the main thread with `INLINE` dispatch in production.
- Do not create a logger (or a Room database) per screen.
- Do not pass raw sensitive values as metadata "just for debugging"; the
  processor accepts only explicit MASK/HASH policies, but that is a guard, not
  permission to log PII.
- Do not rely on `minLevel` to silence audit events — it filters diagnostics
  only. Use `enabled` (or `ILogger.disabled`) when auditing is intentionally off.
