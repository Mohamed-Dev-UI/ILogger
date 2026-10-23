package com.mohamed.dev.ui.ilogger.ui

data class Recipe(
    val id: String,
    val title: String,
    val subtitle: String,
    val tags: List<String>,
    val code: String,
)

/** Every snippet in the Recipes screen is copied from a verified shape in this repo. */
object Recipes {

    val all: List<Recipe> = listOf(
        Recipe(
            id = "log-surface",
            title = "The whole @Log surface",
            subtitle = "One annotation controls lifecycle, payload, routing and dispatch. Defaults shown.",
            tags = listOf("ksp", "@Log"),
            code = """
@Log(
    action = TaskActions.CREATED,        // required, const val, <domain>.<event>
    schemaVersion = 1,                    // 2 -> schemaId "task.created@v2"
    entity = Task::class,                 // else proven from the signature
    entityId = "result",                  // else inferred (insert result, id property, single id param)
    actor = "",                           // e.g. "operatorId" -> Actor.user(operatorId)
    level = LogLevel.AUDIT,               // AUDIT is never sampled or min-level filtered
    category = "",                        // sink routing key; "" = catch-all only
    dispatch = DispatchMode.DEFAULT,      // follow the engine configuration
    metadata = [LogMeta("title")],
    changes = [LogChange(field = "title", from = "old.title", to = "newTitle")],
    before = false,                       // true -> also emit START before the call
    onSuccess = true,                     // success event after the delegate returns
    onFailure = false,                    // true -> FAILURE event, original throwable rethrown
    sampling = -1.0,                      // -1 = inherit; < 1.0 is illegal on AUDIT
    description = "Free-form docs embedded in generated code",
)
suspend fun create(title: String, old: Task, newTitle: String): Long
""".trimIndent(),
        ),
        Recipe(
            id = "gradle",
            title = "Gradle + KSP setup",
            subtitle = "Two artifacts plus the processor. No runtime reflection, no Android dependency.",
            tags = listOf("setup", "gradle"),
            code = """
// gradle/libs.versions.toml
[versions]
ilogger = "0.1.0"
ksp = "2.3.12"

[libraries]
ilogger-annotations = { module = "io.github.mohamed-dev-ui:ilogger-annotations", version.ref = "ilogger" }
ilogger-core = { module = "io.github.mohamed-dev-ui:ilogger-core", version.ref = "ilogger" }
ilogger-ksp = { module = "io.github.mohamed-dev-ui:ilogger-ksp", version.ref = "ilogger" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }

// build.gradle.kts
plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(libs.ilogger.annotations)
    implementation(libs.ilogger.core)
    ksp(libs.ilogger.ksp)

    // optional integrations
    // implementation("io.github.mohamed-dev-ui:ilogger-room")
    // implementation("io.github.mohamed-dev-ui:ilogger-serialization")
    // implementation("io.github.mohamed-dev-ui:ilogger-ktor")
    // testImplementation("io.github.mohamed-dev-ui:ilogger-testing")
}
""".trimIndent(),
        ),
        Recipe(
            id = "minimal",
            title = "Instrument a contract in three lines",
            subtitle = "Annotate the interface, wrap the implementation once, inject the logger.",
            tags = listOf("quickstart", "@Log"),
            code = """
interface TaskRepository {
    @Log(
        action = TaskActions.CREATED,
        entity = Task::class,
        metadata = [LogMeta("title")],
    )
    suspend fun create(title: String): Long
}

val logger = ILogger.create {
    sink(ConsoleLogSink())
    dispatch = DispatchMode.INLINE   // see events synchronously while developing
}

val repository: TaskRepository = RoomTaskRepository(dao).logged(logger)

withActor(Actor.user("u-42", name = "Grace")) {
    withCorrelation("req-abc") {
        repository.create("ship it")   // two events? no: one, attributed and correlated
    }
}
""".trimIndent(),
        ),
        Recipe(
            id = "hilt",
            title = "Hilt + Room production graph",
            subtitle = "The exact shape used by this app: target first, database second, bind third.",
            tags = listOf("android", "hilt", "room"),
            code = """
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides @Singleton
    fun target(): RoomLogTarget =
        RoomLogTarget(retention = RoomLogRetention.keepDays(30))

    @Provides @Singleton
    fun logger(@ApplicationContext context: Context, target: RoomLogTarget): ILogger =
        ILogger.create {
            dispatch = DispatchMode.BUFFERED
            bufferSize = 512
            sink(target)                                    // durable, catch-all
            sink(feed.sink)                                 // optional live UI view
            sink(
                TextFileSink(context.filesDir.resolve("audit/audit.jsonl").toPath(), JsonEventCodec),
                category = "audit",                         // audit events only
            )
        }

    @Provides @Singleton
    fun database(
        @ApplicationContext context: Context,
        logger: ILogger,
        target: RoomLogTarget,
    ): AppDatabase =
        Room.databaseBuilder<AppDatabase>(context, "app.db")
            .withAuditCallback(logger, label = "app.db")
            .build()
            .also { target.bind(it.logDao()) }

    @Provides @Singleton
    fun taskDao(db: AppDatabase, logger: ILogger): TaskDao =
        db.logged(logger).taskDao()                         // generated facade
}

// @HiltAndroidApp Application:
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    logger.flush()                                          // before the OS freezes us
}
""".trimIndent(),
        ),
        Recipe(
            id = "daobindings",
            title = "@LogDao: Hilt and Koin bindings",
            subtitle = "Opt a DAO in once; injection already returns the wrapped instance.",
            tags = listOf("room", "hilt", "koin"),
            code = """
// --- Hilt ---------------------------------------------------------------
@LogDao(hilt = true)
@Dao
interface TaskDao {
    @Insert
    @Log(action = TaskActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: Task): Long
}

class TaskRepository @Inject constructor(private val dao: TaskDao) {
    suspend fun add(task: Task) = dao.insert(task)   // audited, no .logged() here
}

// --- Koin ---------------------------------------------------------------
@LogDao(koin = true)
@Dao
interface AuditDao {
    @Query("SELECT * FROM tasks")
    @Log(action = "task.listed")
    suspend fun all(): List<Task>
}

startKoin {
    modules(AppDatabase_ILogged_KoinModule.module, appModule)
    // appModule must provide AppDatabase and ILogger
}

// --- Explicit adapter (no DI generation) --------------------------------
val dao = db.taskDao().logged(logger)
""".trimIndent(),
        ),
        Recipe(
            id = "ktor",
            title = "Ktor: request audit + ambient context",
            subtitle = "One server install and one client install attribute every call to the request.",
            tags = listOf("server", "client", "ktor"),
            code = """
val auditLogger = ILogger.create {
    sink(ConsoleLogSink())
    sink(TextFileSink(Path.of("logs/audit.jsonl"), JsonEventCodec))
}

embeddedServer(CIO, host = "127.0.0.1", port = 8080) {
    install(HttpAudit) {
        logger = auditLogger
        // authenticated principals, never raw headers in production:
        actorExtractor = { call ->
            call.principal<AuthUser>()?.let { Actor.user(it.id, name = it.name) }
        }
        correlationHeader = "X-Request-Id"          // echoed back when generated
        excludePaths = setOf("/health", "/ready", "/metrics")
        sampling = 1.0                              // per-request audit rate
        failureEvents = HttpFailureEvents.SINGLE    // one event per request
    }

    routing {
        post("/orders") {
            call.withAuditContext("tenant" to "toronto") {
                repository.create("flat white")     // inherits actor + correlation
                call.respondText("created")
            }
        }
    }
}

// Outbound calls: propagates X-Request-Id and traceparent, audits each send.
val http = HttpClient(CIO) {
    install(HttpAuditClient) { logger = auditLogger }
}

withCorrelation("checkout-42") {
    http.get("https://inventory.example/stock")     // X-Request-Id: checkout-42
}

// Server: one "http.request" per call: method, path, status, durationMillis.
// Client: one "http.client.request" per send; transport failures carry the cause.
// 4xx/5xx -> outcome FAILURE; correlation flows across both plugins.
""".trimIndent(),
        ),
        Recipe(
            id = "sinks",
            title = "Custom sink and failure-isolated trigger",
            subtitle = "A sink may fail the operation; a trigger never can.",
            tags = listOf("sinks", "alerts"),
            code = """
class AlertSink(private val alert: (LogEvent) -> Unit) : LogSink {
    override suspend fun write(event: LogEvent) {
        if (event.outcome == Outcome.FAILURE || event.level == LogLevel.ERROR) {
            alert(event)                    // may throw -> ErrorMode applies
        }
    }
}

ILogger.create {
    sink(AlertSink { event -> pager.notify(event.schemaId) })
    onEvents { events -> metrics.record(events) }          // real engine batches
    onEvent(category = "security") { event -> auditVault(event) }
    onDispatchError = { event, sink, error -> telemetry(event, sink, error) }
}

// Raw trigger contract: failures go to onError, never to the producer.
sink(
    TriggerSink(
        onEvents = { events -> myLogDao.insertAll(events.map(::toRow)) },
        onError = { event, error -> log.warn("trigger failed", error) },
    ),
)
""".trimIndent(),
        ),
        Recipe(
            id = "jsonl",
            title = "Durable JSONL with rotation",
            subtitle = "Append-only forensics that ship off-device as one file.",
            tags = listOf("file", "codec"),
            code = """
val logger = ILogger.create {
    dispatch = DispatchMode.BUFFERED
    sink(
        TextFileSink(
            file = File(context.filesDir, "audit/audit.jsonl").toPath(),
            codec = JsonEventCodec,             // one compact JSON object per line
            append = true,
            flushEveryWrite = true,             // durability over throughput
            rotation = TextFileSink.Rotation(
                maxBytesPerFile = 64 * 1024,
                maxFiles = 5,                   // audit.jsonl.1 ... .5
            ),
        ),
        category = "audit",                    // strict, machine-readable path
    )
    sink(ConsoleLogSink(), category = "diagnostics")   // noisy human path
}
// A custom codec is one method:
object CsvEventCodec : EventCodec {
    override fun encode(event: LogEvent) =
        listOf(event.timestampMillis, event.level.name, event.schemaId, event.outcome.name)
            .joinToString(",")
}
""".trimIndent(),
        ),
        Recipe(
            id = "room",
            title = "Four ways to persist into Room",
            subtitle = "Start with the one-liner; move to a target only for lifecycle events.",
            tags = listOf("room", "persistence"),
            code = """
// 1) Library-owned table, one builder call (recommended default)
ILogger.create {
    dispatch = DispatchMode.BUFFERED
    roomLogs(db.logDao(), retention = RoomLogRetention.keepDays(30))
}

// 2) Lifecycle events before build() -> late-bound target
val logs = RoomLogTarget(retention = RoomLogRetention.keepDays(30))
val logger = ILogger.create { sink(logs) }
val db = Room.databaseBuilder<AppDatabase>(context, "app.db")
    .withAuditCallback(logger, label = "app.db")
    .build()
logs.bind(db.logDao())          // queued created/opened events drain here

// 3) Your own columns, one transaction per engine batch
sink(RoomLogSink { events -> myLogDao.insertAll(events.map(::toMyRow)) })

// 4) Failure-isolated observer over the same batches
onEvents { events -> myLogDao.insertAll(events.map(::toMyRow)) }

// Existing database: add the table as a normal migration
Room.databaseBuilder<AppDatabase>(context, "app.db")
    .addMigrations(RoomLogSchema.migration(from = 4, to = 5))
    .build()
""".trimIndent(),
        ),
        Recipe(
            id = "testing",
            title = "Deterministic tests with TestKit",
            subtitle = "INLINE dispatch, fixed clock and sequential ids: no sleeps, no flakes.",
            tags = listOf("tests", "testkit"),
            code = """
val kit = TestKit.create()                  // INLINE + FixedClock + e1, e2, ...
val dao = InMemoryTaskRepository().logged(kit.logger)

runBlocking { dao.insert(Task(title = "x")) }

val event = kit.sink.last()
assertEquals("task.created", event.action)
assertEquals("e1", event.id)
assertEquals("Task", event.entity)
assertEquals(1_700_000_000_000L, event.timestampMillis)

// Async or BUFFERED modes:
val asyncKit = TestKit.create { dispatch = DispatchMode.BUFFERED }
asyncKit.logger.record(action = "task.created", entity = "Task", entityId = "1")
assertTrue(asyncKit.sink.awaitCount(1))
asyncKit.logger.close()
""".trimIndent(),
        ),
        Recipe(
            id = "profiles",
            title = "Configuration profiles",
            subtitle = "Pick one per host; tune bufferSize to worst-case rate × sink latency.",
            tags = listOf("config"),
            code = """
// Development: visible, synchronous, no background worker
ILogger.create {
    sink(ConsoleLogSink())
    dispatch = DispatchMode.INLINE
}

// Tests: deterministic
val kit = TestKit.create()

// Production server: batched, durable, retried
ILogger.create {
    sink(ConsoleLogSink(), category = "console")
    sink(TextFileSink(Path.of("logs/audit.jsonl"), JsonEventCodec))
    dispatch = DispatchMode.BUFFERED
    batchSize = 128
    bufferSize = 8_192
    onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
    retryBaseBackoffMillis = 100
    closeTimeoutMillis = 10_000
    onDispatchError = { _, _, e -> e.printStackTrace() }
}

// Android: small buffer, Room, retention, lifecycle flush
ILogger.create {
    sink(roomTarget)
    dispatch = DispatchMode.BUFFERED
    bufferSize = 512
    closeTimeoutMillis = 2_000
}
// onTrimMemory -> logger.flush()
""".trimIndent(),
        ),
        Recipe(
            id = "context",
            title = "Actors, correlation, causation, suppression",
            subtitle = "All four are ambient coroutine context, merge predictably and never leak.",
            tags = listOf("context", "actors"),
            code = """
withActor(Actor.user("u-42", name = "Grace", attributes = mapOf("team" to "audit"))) {
    withCorrelation("order-1042") {                  // one logical story
        withContextAttributes("tenant" to "acme") {
            withCausation(priorEvent.id) {           // this exists because that happened
                repository.complete(taskId)
            }
        }
    }
}

// Drop an inner layer's duplicate event before allocation:
withLogContext(LogContext(suppressSources = setOf("TaskDao.insert"))) {
    repository.create("ship it")   // TaskRepository event stays; TaskDao event is dropped
}

// Non-suspend boundary: only the thread-local bridge is visible here.
LogContexts.onCurrentThread(LogContext(actor = Actor.service("svc-mobile"))) {
    cache.put(task)                // generated recordNow path
}
""".trimIndent(),
        ),
        Recipe(
            id = "guardrails",
            title = "What the compiler refuses",
            subtitle = "Ambiguity is an error with a Fix, never a silently wrong event.",
            tags = listOf("diagnostics", "safety"),
            code = """
// 1) Two equally plausible ids
@Log(action = "task.moved", entity = Task::class)
suspend fun move(fromId: Long, toId: Long)
// error: Cannot infer entity ID: 2 id-typed parameters ...
// Fix: specify @Log(entityId = "fromId") explicitly.

// 2) Sensitive value without a policy
LogMeta("task.passwordHash")
// error: Select a sensitive value with an explicit policy (MASK/HASH).
// Fix: LogMeta("task.passwordHash", sensitive = SensitiveHandling.HASH).

// 3) Flow-returning function
@Log(action = "task.watch")
fun watch(folder: Folder): Flow<Change>
// error: @Log is not supported on Flow-returning functions.
// Fix: log the subscription, then logger.record(...) at the collection site.

// 4) Vararg element selection
LogMeta("tasks.first().title")
// error: vararg parameter 'tasks' cannot provide chained values.
// Fix: LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count").

// 5) Extractor + sensitive policy
LogMeta("task", extractor = Summary::class, sensitive = SensitiveHandling.HASH)
// error: an extractor defines the output shape; apply the policy inside it.
""".trimIndent(),
        ),
    )
}
