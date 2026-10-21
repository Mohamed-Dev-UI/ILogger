# Room Log Persistence: Triggers + Library-Owned Entity/DAO — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A consumer can persist every emitted `LogEvent` into a Room table with one builder line (`roomLogs(db.logDao())`), or use a generic trigger hook (`onEvent` / `onEvents`) when they own the row shape — the library owns batching, JSON payloads, readiness gating, retention, and the schema contract.

**Architecture:** Two layers. Core adds a `TriggerSink` + `ILoggerBuilder.onEvent/onEvents`: an isolated, batch-aware hook that the engine already knows how to route, buffer, drain and close, so a throwing trigger can never fail or retry the producer. `ilogger-room` adds a canonical `RoomLogEntity` + `RoomLogDao` (Room generates the DAO implementation in the consumer module) and a `RoomLogTarget` sink that maps events with a codec, waits for a late-bound DAO, and sweeps retention — reducing the consumer's work to adding the entity to `@Database` and passing their `RoomLogDao`.

**Tech Stack:** Kotlin 2.2.10 (JVM toolchain 17, `-Xexplicit-api=strict`), kotlinx-coroutines, Room 2.8.5 (KMP JVM/Android), KSP 2.3.12, JUnit 4, bundled SQLite driver; Hilt 2.59.2 only in the Android demo app.

**Spec:** No separate spec. The design below is the spec; both the plan and any spec review travel together.

## Design

### Consumer experience (target)

```kotlin
// Their database — unchanged, apart from one extra entity and one accessor.
@Database(entities = [TaskRow::class, RoomLogEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): RoomLogDao          // library-declared @Dao
}

val db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
    .setDriver(BundledSQLiteDriver())
    .build()

val logger = ILogger.create {
    sink(ConsoleLogSink())
    roomLogs(db.logDao(), retention = RoomLogRetention.keepDays(30))   // <- the whole feature
}
```

That `Room.databaseBuilder(...)` block is the **application's own database**, not library
setup: it is the same block the app would write without ILogger. The library must not build
it (see *Database ownership* below).

For an **ordered logger-before-database** setup (lifecycle events must persist into the
table they describe), the same target is created first and bound after `build()`:

```kotlin
val logs = RoomLogTarget(retention = RoomLogRetention.keepDays(30))
val logger = ILogger.create { sink(logs) }
val db = Room.databaseBuilder<AppDatabase>(...).withAuditCallback(logger).build()
logs.bind(db.logDao())        // queued lifecycle events drain after this
```

For a **custom row shape**, the trigger hook replaces a hand-written sink:

```kotlin
ILogger.create {
    onEvents { events ->                          // whole engine batch, suspend
        myLogDao.insertAll(events.map { it.toMyRow() })
    }
}
```

### Why this shape

- The dispatch engine already owns batching, overflow, backpressure, error modes,
  drain-on-close, and recursion suppression. A trigger is a **sink with a different failure
  contract**, not a second pipeline — no duplicated queue, no second lifecycle.
- Sinks can be retried or propagated by the engine; a persistence trigger that throws must
  not fail the business call. `TriggerSink` swallows (and reports) its own failures, so the
  engine's `ErrorMode`/retry never applies to it.
- Shipping the entity and DAO removes the 13-field mapper, the JSON import, the deferred-DAO
  dance, and the retention query that the current `examples/room` hand-writes. Verified with
  Room 2.8.5 + KSP 2.3.12 (JVM consumer): a `@Entity` compiled into a library jar is accepted
  by the consumer's `@Database`, and Room **generates `RoomLogDao_Impl` in the consumer
  module** for a `@Dao` declared in that jar. Compile and runtime (insert + query through
  bundled SQLite) were both proven before writing this plan.
- Rejected: KSP-generating the entity/DAO into the consumer's sources. Room's processor
  ordering against generated `@Database` references is fragile, schema ownership moves to a
  processor option, and it buys nothing over the library-jar approach that already works.
- Rejected: a library-owned `@Database`. Applications own their database, migration
  versioning, driver and encryption; the library must not open one, and sending logs to a
  second database breaks transactional audit reliability.
- Rejected: shipping Hilt modules in the library. DI is an application concern; the recipe
  lives in docs and the Android sample, so `ilogger-*` keeps zero framework coupling.

### Database ownership (why the `db` block is not in the library)

`AppDatabase` is the application's class: it owns entities, schema version, migrations,
driver, file name, encryption, multiprocess policy and `fallbackToDestructiveMigration`.
A library cannot construct a subclass it has never seen, and it must not try.

A library-owned database is the only way to remove that block, and it is strictly worse:

- logs land in a **second database**, so they cannot join the business transaction that
  produced them (an audit row can survive a rolled-back write, or vanish with a restore);
- two backup/retention/encryption policies, two lifecycles, two migration streams;
- `ilogger-room` is JVM/Android-agnostic today; opening a database would pull `Context`,
  app-storage paths and process policy into the library.

So the rule is: **the app builds and owns its database; the library only speaks to the
`RoomLogDao` it is handed.** The only library-specific line in the block is
`.withAuditCallback(logger)` — and it is optional. Without it there is no logger-before-DB
ordering problem at all.

### Hilt wiring (Android production)

The Hilt module contains the app's normal database provider plus one logger provider; the
library ships no Hilt annotations and adds no Hilt dependency (core stays framework-free).
Pin Hilt **2.59.2 or newer** — 2.58 and earlier fail on AGP 9 with
`Could not find the Android Gradle Plugin (AGP) base extension`.

Simplest graph (no lifecycle events):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder<AppDatabase>(context, "app.db").build()

    @Provides @Singleton
    fun logger(db: AppDatabase): ILogger = ILogger.create {
        sink(ConsoleLogSink())
        roomLogs(db.logDao(), retention = RoomLogRetention.keepDays(30))
    }

    @Provides fun logDao(db: AppDatabase): RoomLogDao = db.logDao()
    @Provides fun taskDao(db: AppDatabase): TaskDao = db.taskDao()
}
```

With lifecycle telemetry, invert the order; `bind` lives inside the database provider so the
app never sees an ordering rule:

```kotlin
@Provides @Singleton
fun roomLogTarget(): RoomLogTarget = RoomLogTarget(retention = RoomLogRetention.keepDays(30))

@Provides @Singleton
fun logger(target: RoomLogTarget): ILogger = ILogger.create {
    sink(ConsoleLogSink())
    sink(target)
}

@Provides @Singleton
fun database(
    @ApplicationContext context: Context,
    logger: ILogger,
    target: RoomLogTarget,
): AppDatabase = Room.databaseBuilder<AppDatabase>(context, "app.db")
    .withAuditCallback(logger)
    .build()
    .also { target.bind(it.logDao()) }
```

There is no Dagger cycle: `RoomLogTarget` → `ILogger` → `AppDatabase` → DAOs. Hilt's
generated component tears everything down with the process; flush on
`Application.onTrimMemory` as the demo already does.

### Schema contract

Table `ilogger_events`; primary key is `LogEvent.id` (so `OnConflictStrategy.REPLACE`
makes retries/redelivery idempotent). Flat columns exist for index-friendly queries; the
`payload` column holds the lossless event produced by the injected `EventCodec` (default
`JsonEventCodec`). Columns are append-only within a major version; `RoomLogSchema.migration`
creates the table for consumers adding it to an existing database.

## Global Constraints

- Kotlin 2.2.10, JVM toolchain 17, `-Xexplicit-api=strict` in `ilogger-core` and
  `ilogger-room` — every new public declaration needs explicit visibility and return type.
- No Room type may reach `ilogger-core`; no Android-only API may reach `ilogger-room`.
- Existing public API is frozen: `RoomLogSink(insertBatch: suspend (List<LogEvent>) -> Unit)`
  keeps its constructor and behavior; `RoomLogEntity`, `RoomLogDao`, `RoomLogTarget`,
  `roomLogs` are additive.
- Room 2.8.5, KSP 2.3.12, JUnit 4. Library modules add no new external libraries; the only
  new third-party dependency in the repository is Hilt in `:app` (Task 5b), pinned at 2.59.2
  because older releases cannot apply to AGP 9.
- Exact names: `TriggerSink`, `onEvent`, `onEvents`, `RoomLogEntity`, `RoomLogDao`,
  `RoomLogSchema`, `RoomLogRetention`, `RoomLogTarget`, `roomLogs`, `toRoomLogEntity`.
- Retention defaults to **off** (`null`); an audit log must not silently delete data.

## Review Focus

- An event recorded **before** `RoomLogTarget.bind(...)` — it must wait, then persist, not
  vanish (`RoomLogTargetTest`: *write waits until the dao is bound*).
- A throwing trigger under `DispatchMode.INLINE` with `ErrorMode.PROPAGATE` — the producer
  must still succeed and other sinks must still receive the event (`TriggerSinkTest`).
- A failing retention purge — the freshly inserted batch must survive and the error must be
  reported, not thrown (`RoomLogTargetTest`: *purge failure does not fail the write*).
- Re-flushing/redelivering the same event — row count must not grow (`examples/room` test:
  *flush is idempotent on the event id*).
- `RoomLogSchema.CREATE_TABLE_SQL` and index SQL — Room's migration validation must accept
  them (`examples/room` test: *migration creates a table Room accepts*).

---

### Task 1: Core trigger hook

**Files:**
- Create: `ilogger-core/src/main/kotlin/io/ilogger/core/LogTrigger.kt`
- Test: `ilogger-core/src/test/kotlin/io/ilogger/core/TriggerSinkTest.kt`

**Interfaces:**
- Consumes: `LogSink`, `LogEvent`, `LogLevel`, `ErrorMode`, `DispatchMode`, `ILoggerBuilder`
  from `ilogger-core`.
- Produces:
  - `public class TriggerSink(private val onEvents: suspend (List<LogEvent>) -> Unit, private val onError: ((LogEvent?, Throwable) -> Unit)? = null) : LogSink`
  - `public fun ILoggerBuilder.onEvent(category: String? = null, onError: ((LogEvent?, Throwable) -> Unit)? = null, block: suspend (LogEvent) -> Unit): Unit`
  - `public fun ILoggerBuilder.onEvents(category: String? = null, onError: ((LogEvent?, Throwable) -> Unit)? = null, block: suspend (List<LogEvent>) -> Unit): Unit`

- [ ] **Step 1: Write the failing tests**

Create `TriggerSinkTest.kt` with a shared event factory and these tests:

```kotlin
private fun event(action: String, id: String = action) = LogEvent(
    id = id,
    timestampMillis = 1_700_000_000_000,
    level = LogLevel.AUDIT,
    action = action,
)

@Test
fun `trigger sink forwards the whole batch in one call`() = runTest {
    val batches = mutableListOf<List<String>>()
    val sink = TriggerSink { events -> batches.add(events.map { it.action }) }
    sink.writeAll(listOf(event("a"), event("b"), event("c")))
    assertEquals(listOf(listOf("a", "b", "c")), batches)
}

@Test
fun `onEvent receives every emitted event`() = runTest {
    val seen = mutableListOf<String>()
    val logger = ILogger.create {
        dispatch = DispatchMode.INLINE
        onEvent { seen.add(it.action) }
    }
    logger.record(action = "task.created")
    logger.record(action = "task.deleted")
    logger.close()
    assertEquals(listOf("task.created", "task.deleted"), seen)
}

@Test
fun `throwing trigger does not fail the producer or other sinks`() = runTest {
    val errors = mutableListOf<Throwable>()
    val written = mutableListOf<String>()
    val logger = ILogger.create {
        dispatch = DispatchMode.INLINE
        onErrorMode(LogLevel.AUDIT, ErrorMode.PROPAGATE)
        sink(object : LogSink {
            override suspend fun write(event: LogEvent) { written.add(event.action) }
        })
        onEvent(onError = { _, error -> errors.add(error) }) { error("db closed") }
    }
    logger.record(action = "task.created")          // must not throw
    logger.close()
    assertEquals(listOf("task.created"), written)
    assertEquals(1, errors.size)
}

@Test
fun `trigger failures are never retried by the engine`() = runTest {
    var calls = 0
    val logger = ILogger.create {
        dispatch = DispatchMode.INLINE
        onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
        retryMaxAttempts = 3
        retryBaseBackoffMillis = 1
        onEvents { calls += 1; throw IllegalStateException("boom") }
    }
    logger.record(action = "task.created")
    logger.close()
    assertEquals(1, calls)
}

@Test
fun `trigger honors category routing`() = runTest {
    val seen = mutableListOf<String>()
    val logger = ILogger.create {
        dispatch = DispatchMode.INLINE
        onEvent(category = "db") { seen.add(it.action) }
    }
    logger.record(action = "http.request", category = "http")
    logger.record(action = "task.created", category = "db")
    logger.close()
    assertEquals(listOf("task.created"), seen)
}

@Test
fun `empty batch never invokes the trigger`() = runTest {
    var calls = 0
    val sink = TriggerSink { calls += 1 }
    sink.writeAll(emptyList())
    assertEquals(0, calls)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :ilogger-core:test --tests "io.ilogger.core.TriggerSinkTest" --no-daemon`
Expected: compilation failure — unresolved `TriggerSink`, `onEvent`, `onEvents`.

- [ ] **Step 3: Implement `LogTrigger.kt`**

`TriggerSink.write(event)` delegates to `writeAll(listOf(event))`. `writeAll` returns
immediately for an empty list; otherwise it invokes `onEvents` inside `try`, rethrows
`kotlinx.coroutines.CancellationException`, and routes any other `Throwable` to
`onError?.invoke(events.firstOrNull(), error)` — never rethrowing. KDoc must state that this
isolation is deliberate and that errors are not retried by the engine. Both builder
extensions just call `sink(TriggerSink(...), category)`; `onEvent` adapts per event.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :ilogger-core:test --tests "io.ilogger.core.TriggerSinkTest" --no-daemon`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add ilogger-core/src/main/kotlin/io/ilogger/core/LogTrigger.kt \
        ilogger-core/src/test/kotlin/io/ilogger/core/TriggerSinkTest.kt
git commit -m "Triggers: isolated batch-aware event hooks on the logger"
```

---

### Task 2: Canonical Room entity, DAO, schema and mapping

**Files:**
- Create: `ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogEntity.kt`
- Create: `ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogDao.kt`
- Create: `ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogSchema.kt`
- Create: `ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogMapping.kt`
- Modify: `ilogger-room/build.gradle.kts` (add `implementation(project(":ilogger-serialization"))`,
  `implementation(libs.kotlinx.coroutines.core)` and `testImplementation(libs.kotlinx.coroutines.test)`)
- Test: `ilogger-room/src/test/kotlin/io/ilogger/room/RoomLogMappingTest.kt`

**Interfaces:**
- Consumes: `LogEvent`, `EventCodec` (`ilogger-core`), `JsonEventCodec`
  (`ilogger-serialization`), Room annotations.
- Produces:
  - `@Entity(tableName = "ilogger_events", indices = [Index("ts"), Index("level"), Index("action")]) public data class RoomLogEntity(...)` with fields
    `public val eventId: String` (`@PrimaryKey`), `public val ts: Long`, `public val level: String`,
    `public val action: String`, `public val actionVersion: Int`, `public val entity: String?`,
    `public val entityId: String?`, `public val actorId: String?`, `public val outcome: String`,
    `public val source: String?`, `public val category: String?`, `public val correlationId: String?`,
    `public val causationId: String?`, `public val payload: String`.
  - `@Dao public interface RoomLogDao` with
    `public suspend fun insertAll(rows: List<RoomLogEntity>)` (`OnConflictStrategy.REPLACE`),
    `public suspend fun recent(limit: Int): List<RoomLogEntity>` (`ORDER BY ts DESC, eventId DESC`),
    `public suspend fun purgeBefore(cutoff: Long): Int`.
  - `public object RoomLogSchema { public const val TABLE: String = "ilogger_events"; public val CREATE_TABLE_SQL: String; public val INDEX_SQL: List<String>; public fun migration(startVersion: Int, endVersion: Int): Migration }`.
  - `public fun LogEvent.toRoomLogEntity(codec: EventCodec = JsonEventCodec): RoomLogEntity`.

- [ ] **Step 1: Write the failing mapping tests**

```kotlin
@Test
fun `maps identity semantics and context into flat columns`() {
    val event = LogEvent(
        id = "e1", timestampMillis = 1_000, level = LogLevel.AUDIT,
        action = "task.created", actionVersion = 2,
        actor = Actor.user("u1"), entity = "Task", entityId = "42",
        source = "TaskDao.insert", category = "db",
        correlationId = "c1", causationId = "c0",
    )
    val row = event.toRoomLogEntity(EventCodec { "payload" })
    assertEquals("e1", row.eventId)
    assertEquals(1_000, row.ts)
    assertEquals("AUDIT", row.level)
    assertEquals("task.created", row.action)
    assertEquals(2, row.actionVersion)
    assertEquals("u1", row.actorId)
    assertEquals("Task", row.entity)
    assertEquals("42", row.entityId)
    assertEquals("SUCCESS", row.outcome)
    assertEquals("db", row.category)
    assertEquals("c1", row.correlationId)
    assertEquals("c0", row.causationId)
    assertEquals("payload", row.payload)
}

@Test
fun `default codec stores a lossless json payload`() {
    val event = LogEvent(
        id = "e1", timestampMillis = 1, level = LogLevel.AUDIT,
        action = "task.created",
        metadata = mapOf("title" to "audit everything"),
        changes = listOf(FieldChange("completed", false, true)),
    )
    val payload = event.toRoomLogEntity().payload
    assertTrue(payload.contains("\"action\":\"task.created\""))
    assertTrue(payload.contains("\"title\""))
    assertTrue(payload.contains("\"completed\""))
}

@Test
fun `schema sql declares every mapped column and index`() {
    val sql = RoomLogSchema.CREATE_TABLE_SQL
    for (column in listOf("eventId", "ts", "level", "action", "actionVersion", "entity",
        "entityId", "actorId", "outcome", "source", "category", "correlationId",
        "causationId", "payload")) {
        assertTrue("missing $column in $sql", sql.contains("`$column`"))
    }
    assertEquals(
        listOf(
            "CREATE INDEX IF NOT EXISTS `index_ilogger_events_ts` ON `ilogger_events` (`ts`)",
            "CREATE INDEX IF NOT EXISTS `index_ilogger_events_level` ON `ilogger_events` (`level`)",
            "CREATE INDEX IF NOT EXISTS `index_ilogger_events_action` ON `ilogger_events` (`action`)",
        ),
        RoomLogSchema.INDEX_SQL,
    )
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :ilogger-room:test --tests "io.ilogger.room.RoomLogMappingTest" --no-daemon`
Expected: compilation failure — unresolved symbols.

- [ ] **Step 3: Implement the entity, DAO, schema and mapper**

`CREATE_TABLE_SQL` must match Room's expected schema exactly (column names, `TEXT`/`INTEGER`,
`NOT NULL`, `PRIMARY KEY(eventId)`), and `INDEX_SQL` must use Room's default index names
(`index_ilogger_events_<column>`) so migration validation accepts it. `migration(...)` returns
an anonymous `androidx.room.migration.Migration` whose `migrate(connection: SQLiteConnection)`
executes `CREATE_TABLE_SQL` then each index statement via `androidx.sqlite.execSQL`. Mapper
copies flat fields with `level.name` / `outcome.name`, `actor?.id`, and `codec.encode(this)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :ilogger-room:test --tests "io.ilogger.room.RoomLogMappingTest" --no-daemon`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogEntity.kt \
        ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogDao.kt \
        ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogSchema.kt \
        ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogMapping.kt \
        ilogger-room/src/test/kotlin/io/ilogger/room/RoomLogMappingTest.kt \
        ilogger-room/build.gradle.kts
git commit -m "Room logs: ship the canonical entity, DAO and migration schema"
```

---

### Task 3: `RoomLogRetention` + `RoomLogTarget`

**Files:**
- Create: `ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogRetention.kt`
- Create: `ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogTarget.kt`
- Test: `ilogger-room/src/test/kotlin/io/ilogger/room/RoomLogTargetTest.kt`
- Test helper: `ilogger-room/src/test/kotlin/io/ilogger/room/FakeRoomLogDao.kt` (implements
  `RoomLogDao`; records inserted rows and purge cutoffs, optional `purgeError`) plus a file-local
  `private fun event(id: String, action: String = id)` factory used by Tasks 3–5

**Interfaces:**
- Consumes: `RoomLogDao`, `toRoomLogEntity`, `EventCodec`, `LogClock`, `SystemClock`,
  `CompletableDeferred`.
- Produces:
  - `public class RoomLogRetention(public val keepMillis: Long, public val sweepEveryMillis: Long)` with
    `public companion object { public fun keepDays(days: Int, sweepEveryHours: Int = 24): RoomLogRetention }`;
    `require(keepMillis >= 0)`, `require(sweepEveryMillis > 0)`, and `keepDays` requires positive
    arguments before multiplying by `86_400_000L` / `3_600_000L`.
  - `public class RoomLogTarget(private val codec: EventCodec = JsonEventCodec, private val retention: RoomLogRetention? = null, private val clock: LogClock = SystemClock, private val onError: ((Throwable) -> Unit)? = null) : LogSink`
    with `public fun bind(dao: RoomLogDao): Unit`. `write(event)` delegates to `writeAll(listOf(event))`.

- [ ] **Step 1: Write the failing tests**

Use `FakeRoomLogDao` recording `inserted: MutableList<RoomLogEntity>` and
`purged: MutableList<Long>`, with `var purgeError: Throwable?`; declare the shared
`event(id, action)` factory in the same test file.

```kotlin
@Test
fun `write waits until the dao is bound`() = runBlocking {
    val dao = FakeRoomLogDao()
    val target = RoomLogTarget(EventCodec { "p" })
    val write = async(Dispatchers.Default) { target.writeAll(listOf(event("e1"))) }
    delay(50)
    assertTrue(dao.inserted.isEmpty())
    target.bind(dao)
    write.await()
    assertEquals(listOf("e1"), dao.inserted.map { it.eventId })
}

@Test
fun `retention sweeps on first write then at most once per interval`() = runBlocking {
    val clock = FixedClock(1_000_000_000)
    val dao = FakeRoomLogDao()
    val target = RoomLogTarget(
        codec = EventCodec { "p" },
        retention = RoomLogRetention(keepMillis = 86_400_000, sweepEveryMillis = 3_600_000),
        clock = clock,
    )
    target.bind(dao)
    target.writeAll(listOf(event("e1")))
    assertEquals(listOf(1_000_000_000L - 86_400_000), dao.purged)
    clock.set(clock.nowMillis() + 60_000)
    target.writeAll(listOf(event("e2")))
    assertEquals(1, dao.purged.size)
    clock.set(clock.nowMillis() + 3_600_000)
    target.writeAll(listOf(event("e3")))
    assertEquals(2, dao.purged.size)
}

@Test
fun `purge failure does not fail the write`() = runBlocking {
    val errors = mutableListOf<Throwable>()
    val dao = FakeRoomLogDao()
    val target = RoomLogTarget(
        codec = EventCodec { "p" },
        retention = RoomLogRetention(keepMillis = 1_000, sweepEveryMillis = 1_000),
        clock = FixedClock(10_000),
        onError = { errors.add(it) },
    )
    dao.purgeError = IllegalStateException("database locked")
    target.bind(dao)
    target.writeAll(listOf(event("e1")))
    assertEquals(1, dao.inserted.size)
    assertEquals(1, errors.size)
}

@Test
fun `no retention policy never purges`() = runBlocking {
    val dao = FakeRoomLogDao()
    val target = RoomLogTarget(EventCodec { "p" })
    target.bind(dao)
    target.writeAll(listOf(event("e1")))
    assertTrue(dao.purged.isEmpty())
}

@Test
fun `empty batch never touches the dao`() = runBlocking {
    val dao = FakeRoomLogDao()
    val target = RoomLogTarget(EventCodec { "p" })
    target.bind(dao)
    target.writeAll(emptyList())
    assertTrue(dao.inserted.isEmpty())
}

@Test
fun `close is a no-op`() {
    val target = RoomLogTarget(EventCodec { "p" })
    target.close()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :ilogger-room:test --tests "io.ilogger.room.RoomLogTargetTest" --no-daemon`
Expected: compilation failure — unresolved `RoomLogTarget` / `RoomLogRetention`.

- [ ] **Step 3: Implement retention and target**

`RoomLogTarget` holds `private val boundDao = CompletableDeferred<RoomLogDao>()`; `bind`
completes it (second bind is ignored). `writeAll` returns for an empty list, awaits the DAO,
maps the batch, calls `insertAll`, then — only when a policy exists and
`lastSweepMillis == null || now - lastSweepMillis >= sweepEveryMillis` — calls
`purgeBefore(now - keepMillis)`. Record `lastSweepMillis` only after a successful purge; catch
`CancellationException` and rethrow, catch other throwables and pass them to `onError`.
KDoc must state: writes are awaited inside the engine's dispatch path, `insertAll` must use
`REPLACE` (library contract), events queued before `bind` wait rather than disappear, and
retention is off by default. Also state that a target must be bound before `flush()`/`close()`;
otherwise the draining worker waits for the DAO until `closeTimeoutMillis` elapses. Insert
failures are not swallowed: they propagate to the engine's `ErrorMode` (so `AUDIT` uses the
configured `RETRY` with `REPLACE` making redelivery idempotent).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :ilogger-room:test --tests "io.ilogger.room.RoomLogTargetTest" --no-daemon`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogRetention.kt \
        ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogTarget.kt \
        ilogger-room/src/test/kotlin/io/ilogger/room/RoomLogTargetTest.kt
git commit -m "Room logs: late-bound target with retention sweeps"
```

---

### Task 4: One-line builder wiring

**Files:**
- Create: `ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogs.kt`
- Test: `ilogger-room/src/test/kotlin/io/ilogger/room/RoomLogsTest.kt`

**Interfaces:**
- Consumes: `RoomLogTarget`, `RoomLogRetention`, `RoomLogDao`, `EventCodec`, `JsonEventCodec`,
  `ILoggerBuilder`.
- Produces: `public fun ILoggerBuilder.roomLogs(dao: RoomLogDao, codec: EventCodec = JsonEventCodec, retention: RoomLogRetention? = null): Unit`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `roomLogs persists buffered events through the dao`() = runBlocking {
    val dao = FakeRoomLogDao()
    val logger = ILogger.create {
        batchSize = 4
        flushIntervalMillis = 1
        roomLogs(dao)
    }
    repeat(6) { logger.recordNow(action = "event.$it") }
    logger.close()
    assertEquals(6, dao.inserted.size)
    assertEquals(
        (0..5).map { "event.$it" },
        dao.inserted.map { it.action },
    )
}
```

`FakeRoomLogDao` comes from Task 3; do not duplicate it.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :ilogger-room:test --tests "io.ilogger.room.RoomLogsTest" --no-daemon`
Expected: compilation failure — unresolved `roomLogs`.

- [ ] **Step 3: Implement `roomLogs`**

Create a bound `RoomLogTarget(codec = codec, retention = retention)`, call `bind(dao)`, and
pass it to `sink(...)`. Do not touch `dispatch`—the engine default is already `BUFFERED`; only
`batchSize`/`flushIntervalMillis` are the consumer's choice.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :ilogger-room:test --tests "io.ilogger.room.RoomLogsTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ilogger-room/src/main/kotlin/io/ilogger/room/RoomLogs.kt \
        ilogger-room/src/test/kotlin/io/ilogger/room/RoomLogsTest.kt
git commit -m "Room logs: one-line logger wiring via roomLogs()"
```

---

### Task 5: Real Room integration — example and tests

**Files:**
- Modify: `examples/room/src/main/kotlin/example/room/Database.kt` (drop `LogEntity`,
  `toLogRow`, `LogDao`; add `RoomLogEntity` to `@Database`; `abstract fun logDao(): RoomLogDao`)
- Modify: `examples/room/src/main/kotlin/example/room/Main.kt` (use `RoomLogTarget`, remove
  `CompletableDeferred`/codec mapping, print `payload`)
- Modify: `examples/room/build.gradle.kts` (add `testImplementation(libs.junit)`,
  `testImplementation(libs.kotlinx.coroutines.test)`)
- Test: `examples/room/src/test/kotlin/example/room/RoomPersistenceTest.kt` (also declares the
  test-only `MigrationDb` and the `tempDbFile()` / `event()` helpers)

**Interfaces:**
- Consumes: everything from Tasks 1–4, `withAuditCallback`, `JsonEventCodec` (through the
  library default), `BundledSQLiteDriver`, `androidx.room.migration.Migration`,
  `androidx.sqlite.execSQL`.
- Produces: verified consumer recipe (no new library API).

- [ ] **Step 1: Write the failing integration tests**

Add the shared factory `private fun event(id: String, action: String = id) = LogEvent(...)` to
the test file.

```kotlin
@Test
fun `lifecycle and audit events persist with their payload`() = runBlocking {
    val file = tempDbFile()
    val logs = RoomLogTarget(retention = RoomLogRetention.keepDays(30))
    val logger = ILogger.create {
        sink(logs)
        dispatch = DispatchMode.BUFFERED
        batchSize = 4
        flushIntervalMillis = 1
    }
    val db = Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .withAuditCallback(logger, label = file.name)
        .build()
    logs.bind(db.logDao())
    withActor(Actor.user("u-42")) {
        db.taskDao().logged(logger).insert(TaskRow(title = "persisted"))
    }
    logger.close()
    val rows = db.logDao().recent(limit = 20)
    assertTrue(rows.any { it.action == "room.database.opened" })
    assertTrue(rows.any { it.action == "task.created" })
    val created = rows.first { it.action == "task.created" }
    assertTrue(created.payload.contains("\"action\":\"task.created\""))
    assertEquals("u-42", created.actorId)
    db.close(); file.delete()
}

@Test
fun `flush is idempotent on the event id`() = runBlocking {
    val file = tempDbFile()
    val db = Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .build()
    val logger = ILogger.create { roomLogs(db.logDao(), retention = null) }
    logger.recordNow(action = "app.started")
    logger.flush()
    logger.flush()
    logger.close()
    assertEquals(1, db.logDao().recent(10).size)
    db.close(); file.delete()
}

@Test
fun `migration creates a table Room accepts`() = runBlocking {
    val file = tempDbFile()
    BundledSQLiteDriver().open(file.absolutePath).use { connection ->
        connection.execSQL("PRAGMA user_version = 1")
    }
    val db = Room.databaseBuilder<MigrationDb>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(RoomLogSchema.migration(1, 2))
        .build()
    db.logDao().insertAll(listOf(event("e1").toRoomLogEntity()))
    assertEquals(listOf("e1"), db.logDao().recent(10).map { it.eventId })
    db.close(); file.delete()
}
```

`MigrationDb` is a test-only database declared in the test file:
`@Database(entities = [RoomLogEntity::class], version = 2, exportSchema = false)` with
`abstract fun logDao(): RoomLogDao`. `tempDbFile()` returns a fresh file under
`java.io.tmpdir` (unique name per call).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :examples:room:test --no-daemon`
Expected: compilation failure — the tests call `db.logDao().recent(...)` while the current
`AppDatabase.logDao()` still returns the app-owned `LogDao`, and `RoomLogTarget` is not wired
into the example yet.

- [ ] **Step 3: Rewrite the example onto the library entity and target**

`Database.kt`: `@Database(entities = [TaskRow::class, RoomLogEntity::class], version = 2, exportSchema = false)`;
delete the app-owned log entity, mapper, and DAO; keep `TaskDao` untouched.

`Main.kt`: create `RoomLogTarget(retention = RoomLogRetention.keepDays(30))` before the
logger; `sink(ConsoleLogSink())` + `sink(logs)`; build the DB with `withAuditCallback`; call
`logs.bind(db.logDao())`; keep the DAO-flows demo and the read-back/recent print using
`db.logDao().recent(limit = 8)` (`row.payload` in place of `payloadJson`). The demo stays
runnable from a clean `java.io.tmpdir` file.

- [ ] **Step 4: Run the tests and the example**

Run: `./gradlew :examples:room:test --no-daemon` → PASS (3 tests).

Run: `./gradlew :examples:room:run --no-daemon`
Expected: console audit lines, then a `log_events` section listing rows with
`room.database.opened`, `task.created`, `app.stopped`, each with a JSON `payload`; exit code 0.

- [ ] **Step 5: Commit**

```bash
git add examples/room/src/main/kotlin/example/room/Database.kt \
        examples/room/src/main/kotlin/example/room/Main.kt \
        examples/room/src/test/kotlin/example/room/RoomPersistenceTest.kt \
        examples/room/build.gradle.kts
git commit -m "Example: persist audit events through the shipped Room log table"
```

---

### Task 5b: Hilt-wired Android sample (`:app`)

**Files:**
- Modify: `gradle/libs.versions.toml` (add `hilt = "2.59.2"`, `hilt-android`,
  `hilt-compiler` libraries, and the `hilt` plugin alias)
- Modify: `build.gradle.kts` (apply the Hilt plugin `apply false`)
- Modify: `app/build.gradle.kts` (Hilt + Room KSP, `:ilogger-room`,
  `:ilogger-serialization`)
- Create: `app/src/main/java/com/mohamed/dev/ui/ilogger/logging/AppDatabase.kt`
- Create: `app/src/main/java/com/mohamed/dev/ui/ilogger/logging/LoggingModule.kt`
- Create: `app/src/main/java/com/mohamed/dev/ui/ilogger/logging/EventBuffer.kt`
- Modify: `app/src/main/java/com/mohamed/dev/ui/ilogger/logging/ILoggerApplication.kt`
  (`@HiltAndroidApp`, injected logger, remove the `AppLogger` object)
- Modify: `app/src/main/java/com/mohamed/dev/ui/ilogger/MainActivity.kt`
  (`@AndroidEntryPoint`, injected logger/buffer/repository; composable takes them as parameters)

**Interfaces:**
- Consumes: `RoomLogEntity`, `RoomLogDao`, `RoomLogTarget`, `RoomLogRetention`, `roomLogs`
  (Tasks 2–4); `logged()` (existing KSP output); `@HiltAndroidApp`, `@AndroidEntryPoint`,
  `@HiltViewModel`-free singleton injection.
- Produces: a compiled, Hilt-wired consumer that demonstrates the recommended production
  shape; no new library API.

- [ ] **Step 1: Add the Hilt toolchain**

Catalog: `hilt = "2.59.2"` (first release line that supports AGP 9; 2.58 fails with
`Could not find the Android Gradle Plugin (AGP) base extension`), libraries
`com.google.dagger:hilt-android` + `com.google.dagger:hilt-android-compiler`, and plugin
`com.google.dagger.hilt.android`. Root build applies it `apply false`.

`app/build.gradle.kts`: apply `alias(libs.plugins.hilt)`, add
`ksp(libs.hilt.compiler)`, `implementation(libs.hilt.android)`,
`implementation(project(":ilogger-room"))`, `implementation(project(":ilogger-serialization"))`,
`ksp(libs.room.compiler)`. Room runtime arrives through `:ilogger-room`'s `api` dependency.

- [ ] **Step 2: Declare the demo database and DI module**

`AppDatabase.kt`:

```kotlin
@Database(entities = [RoomLogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): RoomLogDao
}
```

`LoggingModule.kt` uses the **simplest graph** from the Design section (no
`withAuditCallback`): `AppDatabase` from `@ApplicationContext`, `ILogger` built with
`sink(eventBuffer.sink)` + `roomLogs(db.logDao(), retention = RoomLogRetention.keepDays(30))`,
`RoomLogDao`/`TaskDao`-free `EventBuffer`, and
`@Provides @Singleton fun taskRepository(logger: ILogger): TaskRepository = InMemoryTaskRepository().logged(logger)`.
`EventBuffer` is `@Singleton class EventBuffer @Inject constructor()` exposing
`val lines: StateFlow<List<String>>` and an internal `val sink: LogSink` that prepends
`DefaultTextEventCodec.encode(event)` capped at 50 entries (the existing behavior).

- [ ] **Step 3: Wire the Application and Activity**

`ILoggerApplication`: `@HiltAndroidApp class ILoggerApplication : Application()`,
`@Inject lateinit var logger: ILogger`, `onTrimMemory` calls `logger.flush()`. Delete the
`AppLogger` object. `MainActivity`: `@AndroidEntryPoint`, `@Inject lateinit var logger: ILogger`,
`@Inject lateinit var eventBuffer: EventBuffer`, `@Inject lateinit var repository: TaskRepository`;
pass them into `TaskAuditScreen(logger, eventBuffer.lines.collectAsState().value, repository, ...)`.

- [ ] **Step 4: Build the graph**

Run: `./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`; Dagger validates the whole graph at compile time (no cycles, no
missing bindings). If Hilt 2.59.2 fails with a Kotlin/KSP metadata error, try `2.60.1` before
changing anything else. If both fail on this AGP/Kotlin combination, keep the docs recipe and
report the blocker instead of disabling Hilt checks.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts \
        app/src/main/java/com/mohamed/dev/ui/ilogger/logging/AppDatabase.kt \
        app/src/main/java/com/mohamed/dev/ui/ilogger/logging/LoggingModule.kt \
        app/src/main/java/com/mohamed/dev/ui/ilogger/logging/EventBuffer.kt \
        app/src/main/java/com/mohamed/dev/ui/ilogger/logging/ILoggerApplication.kt \
        app/src/main/java/com/mohamed/dev/ui/ilogger/MainActivity.kt
git commit -m "Demo: Hilt-wired Room-backed logging sample"
```

---

### Task 6: Documentation, changelog, full verification

**Files:**
- Modify: `docs/19-room-integration.md` (new "Layer 4 — one-line persistence" and a
  "custom table via triggers" section; explicitly note the `RoomLogTarget` + `bind` ordering
  recipe, custom-entity escape hatch, retention default-off, and the migration helper)
- Modify: `docs/21-android-app-guide.md` (add the Hilt module recipe and the
  why-the-app-owns-the-database rule; point at `:app` as the runnable sample)
- Modify: `docs/14-sinks.md` (short "Triggers" section: batch-aware, failure-isolated, link 19)
- Modify: `README.md` (module table row for `ilogger-room`)
- Modify: `CHANGELOG.md` (Unreleased → Added)

- [ ] **Step 1: Update the docs with the verified recipe**

Include the exact three snippets from the Design section (simple, lifecycle, Hilt). In
`19-room-integration.md`, keep the existing app-owned `RoomLogSink` layer as the custom-schema
path and add a decision table:

| Need | Use |
|---|---|
| Store everything, no mapping code | `roomLogs(db.logDao())` |
| Lifecycle events before `build()` | `RoomLogTarget` + `bind` |
| Your own columns/entity | `RoomLogSink { ... }` or `onEvents { ... }` |
| Reuse the event rows | `RoomLogDao.recent` / `purgeBefore` |
| Android with Hilt | the `LoggingModule` recipe in [21](21-android-app-guide.md) |

- [ ] **Step 2: Add the changelog entry**

Under `## Unreleased` → `### Added`: triggers (`onEvent`/`onEvents`), `RoomLogEntity`,
`RoomLogDao`, `RoomLogSchema` (+ `migration`), `RoomLogRetention`, `RoomLogTarget`,
`roomLogs()`, and the updated example.

- [ ] **Step 3: Run the full verification suite**

```bash
./gradlew :ilogger-core:test :ilogger-room:test :examples:room:test \
          :ksp-tests:test :ksp-diagnostics:test :ilogger-serialization:test --no-daemon
./gradlew :examples:room:run --no-daemon
./gradlew :app:assembleDebug --no-daemon
./gradlew :ilogger-room:publishToMavenLocal --no-daemon
```

Expected: all tasks `BUILD SUCCESSFUL`; the example prints persisted rows.

- [ ] **Step 4: Commit**

```bash
git add docs/19-room-integration.md docs/14-sinks.md README.md CHANGELOG.md
git commit -m "Docs: document triggers and one-line Room log persistence"
```

## Out of scope

- No library-owned `@Database`, no database creation, no driver or migration ownership.
- No Hilt/Dagger dependency or annotations in any `ilogger-*` module; DI examples are app-side.
- No lazy DAO supplier API: `roomLogs(dao)` and `RoomLogTarget.bind` cover both orders without
  building the database on a logging worker thread.
- No generation of consumer-specific entities (custom columns stay a `RoomLogSink`/`onEvents`
  map).
- No runtime add/remove of triggers after `ILogger.create` (builder-level only).
- No encryption/reporting/UI — retention, not redaction, is the only data-lifecycle feature.

## Execution notes

- Run Tasks 1–2 in parallel only if the executor can keep the two test files separate; Task 3
  depends on Task 2's `RoomLogEntity`/`toRoomLogEntity`; Task 4 depends on Task 3; Task 5
  depends on 1–4; Task 5b depends on 2–4 (its build is independent of Task 5); Task 6 last.
- Hilt is deliberately **not** required for the library to work. If Task 5b is blocked by the
  AGP 9/KSP toolchain, do not add workarounds to the library; ship the recipe in docs and the
  JVM Room example, and record the exact failing versions in the handoff.
- The library-jar DAO generation was verified with Room 2.8.5 + KSP 2.3.12. If a future Room
  version stops generating implementations for dependency DAOs, the fallback is to document a
  three-line consumer DAO over `RoomLogEntity` (no API change to `roomLogs` if it accepts the
  DAO type the consumer declares — at that point accept an insert lambda instead).
- The existing `RoomLogSinkTest` must keep passing unchanged; it is the regression guard for
  the custom-schema path.
