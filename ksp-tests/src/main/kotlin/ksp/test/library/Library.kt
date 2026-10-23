package ksp.test.library

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogActions
import io.ilogger.annotations.LogChange
import io.ilogger.annotations.LogConfig
import io.ilogger.annotations.LogLevel
import io.ilogger.annotations.LogMeta
import io.ilogger.annotations.LogSensitive
import io.ilogger.annotations.NoLog
import io.ilogger.annotations.OnCancellation
import io.ilogger.annotations.SensitiveHandling
import io.ilogger.core.LogValueExtractor
import io.ilogger.core.ValueAdapter
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Test domain model. Every property is a candidate for compile-time selection. */
data class Task(
    val id: Long,
    val title: String,
    val priority: Int,
    val status: TaskStatus,
    val tags: List<String>,
)

enum class TaskStatus { OPEN, DONE, CLOSED }

/**
 * Canonical action codes (annotation values are compile-time constants) and
 * the module's declared action registry (G-22). `generated.operation` is
 * emitted by `:ksp-tests-generator`, not by sources in this module.
 */
@LogActions(
    TaskActions.CREATED,
    TaskActions.UPDATED,
    TaskActions.CLOSED,
    TaskActions.DELETED,
    TaskActions.PURGED,
    TaskActions.FOUND_ID,
    TaskActions.BATCH_CREATED,
    TaskActions.ASSIGNED,
    TaskActions.SUMMARIZED,
    TaskActions.RESERVED_SAVED,
    TaskActions.RESERVED_LOGIN,
    TaskActions.RESERVED_FAILED,
    TaskActions.BASE_STORED,
    TaskActions.BASE_LABELED,
    TaskActions.COUNTED_UP,
    TaskActions.ARCHIVED,
    TaskActions.RENAMED,
    TaskActions.TAG_COUNTED,
    TaskActions.GENERIC_STORED,
    TaskActions.GENERIC_TRANSFORMED,
    TaskActions.WRAPPED_SAVED,
    TaskActions.NULLABLE_SAVED,
    TaskActions.REGISTERED,
    TaskActions.OBSERVED,
    TaskActions.WATCHED,
    TaskActions.SCORED,
    TaskActions.CONFIG_STORED,
    TaskActions.CONFIG_WARNED,
    TaskActions.CONFIG_AUDITED,
    TaskActions.CONFIG_MASKED,
    TaskActions.CANCEL_DEFAULT,
    TaskActions.CANCEL_LOGGED,
    TaskActions.SHARED_AUDIT,
    TaskActions.PARAM_MASKED,
    TaskActions.OMIT_META,
    TaskActions.OMIT_CONFIG,
    TaskActions.ACTOR_MASKED,
    TaskActions.ACTOR_OMITTED,
    TaskActions.RETURN_MASKED,
    TaskActions.GENERATED_OPERATION,
    TaskActions.INJECTED_SUMMARY,
    TaskActions.UNSAFE_SUMMARY,
    TaskActions.THROWING_SUMMARY,
    TaskActions.OBJECT_SUMMARY,
)
object TaskActions {
    const val CREATED = "task.created"
    const val UPDATED = "task.updated"
    const val CLOSED = "task.closed"
    const val DELETED = "task.deleted"
    const val PURGED = "task.purged"
    const val FOUND_ID = "task.found.byId"
    const val BATCH_CREATED = "task.batchCreated"
    const val ASSIGNED = "task.assigned"
    const val SUMMARIZED = "task.summarized"
    const val RESERVED_SAVED = "reserved.saved"
    const val RESERVED_LOGIN = "reserved.login"
    const val RESERVED_FAILED = "reserved.failed"
    const val BASE_STORED = "task.baseStored"
    const val BASE_LABELED = "task.baseLabeled"
    const val COUNTED_UP = "task.countedUp"
    const val ARCHIVED = "task.archived"
    const val RENAMED = "task.renamed"
    const val TAG_COUNTED = "task.tagCounted"
    const val GENERIC_STORED = "task.genericStored"
    const val GENERIC_TRANSFORMED = "task.genericTransformed"
    const val WRAPPED_SAVED = "task.wrappedSaved"
    const val NULLABLE_SAVED = "task.nullableSaved"
    const val REGISTERED = "task.registered"
    const val OBSERVED = "task.observed"
    const val WATCHED = "task.watched"
    const val SCORED = "task.scored"
    const val CONFIG_STORED = "task.configStored"
    const val CONFIG_WARNED = "task.configWarned"
    const val CONFIG_AUDITED = "task.configAudited"
    const val CONFIG_MASKED = "task.configMasked"
    const val CANCEL_DEFAULT = "cancel.default"
    const val CANCEL_LOGGED = "cancel.logged"
    const val SHARED_AUDIT = "audit.shared"
    const val PARAM_MASKED = "task.paramMasked"
    const val OMIT_META = "task.omitMeta"
    const val OMIT_CONFIG = "task.omitConfig"
    const val ACTOR_MASKED = "task.actorMasked"
    const val ACTOR_OMITTED = "task.actorOmitted"
    const val RETURN_MASKED = "task.returnMasked"
    const val GENERATED_OPERATION = "generated.operation"
    const val INJECTED_SUMMARY = "task.injectedSummary"
    const val UNSAFE_SUMMARY = "task.unsafeSummary"
    const val THROWING_SUMMARY = "task.throwingSummary"
    const val OBJECT_SUMMARY = "task.objectSummary"
}

/**
 * Explicit extractor: converts a whole [Task] to a log-safe value. Requires a
 * public no-argument constructor — exactly what generated code instantiates.
 */
class TaskSummaryExtractor : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = value.title
}

/** Vararg parameters can only be converted as a whole (see `@Log` grammar). */
class BatchSizeExtractor : LogValueExtractor<Array<out Task>> {
    override fun extract(value: Array<out Task>): Any? = value.size
}

/**
 * The instrumented interface. Each member exercises one codegen path:
 *
 * - [insert]        — suspend, result-derived entity id, param-derived metadata
 * - [update]        — changes (before/after) and enum metadata
 * - [close]         — constant + context metadata, explicit entity id, category
 * - [delete]        — START (before) + SUCCESS on a Unit function
 * - [purge]         — FAILURE path with onFailure
 * - [findById]      — non-suspend function (recordNow)
 * - [insertAll]     — vararg + List result (batch: provable-no entity id)
 * - [assign]        — explicit actor override
 * - [summarize]     — explicit extractor metadata
 * - inline-prefixed — dispatch override
 * - passthrough (no `@Log`) — untouched delegation
 */
interface TaskRepository {

    @Log(
        action = TaskActions.CREATED,
        metadata = [LogMeta("task.title"), LogMeta("task.priority")],
        description = "Creates a task.",
    )
    suspend fun insert(task: Task): Long

    @Log(
        action = TaskActions.UPDATED,
        metadata = [LogMeta("task.status")],
        changes = [LogChange(field = "title", from = "task.title", to = "newTitle")],
    )
    suspend fun update(task: Task, newTitle: String): Boolean

    @Log(
        action = TaskActions.CLOSED,
        level = LogLevel.INFO,
        category = "workflows",
        metadata = [LogMeta("'closure'", name = "event"), LogMeta("context.requestId")],
    )
    suspend fun close(taskId: Long)

    @Log(action = TaskActions.DELETED, before = true)
    suspend fun delete(task: Task)

    @Log(action = TaskActions.PURGED, onFailure = true)
    suspend fun purge(task: Task)

    @Log(action = TaskActions.FOUND_ID)
    fun findById(taskId: Long): Task

    @Log(
        action = TaskActions.BATCH_CREATED,
        metadata = [LogMeta("tasks", extractor = BatchSizeExtractor::class, name = "count")],
    )
    suspend fun insertAll(vararg tasks: Task): List<Long>

    @Log(action = TaskActions.ASSIGNED, actor = "userId")
    suspend fun assign(task: Task, userId: String)

    @Log(
        action = TaskActions.SUMMARIZED,
        metadata = [LogMeta("task", extractor = TaskSummaryExtractor::class, name = "summary")],
    )
    suspend fun summarize(task: Task): Int

    @Log(
        action = TaskActions.DELETED,
        schemaVersion = 2,
        category = "emergency",
        dispatch = DispatchMode.INLINE,
        metadata = [LogMeta("task.id")],
    )
    suspend fun inlineDelete(task: Task)

    @Log(action = TaskActions.CANCEL_DEFAULT, onFailure = true)
    suspend fun cancelDefault(taskId: Long)

    @Log(action = TaskActions.CANCEL_LOGGED, onFailure = true, onCancellation = OnCancellation.LOG)
    suspend fun cancelLogged(taskId: Long)

    suspend fun passthrough(value: Int): Int
}

/**
 * Parameter names deliberately collide with names the generated wrapper uses
 * (`result`, `context`, `logger`, `error`, and even the `iloggerResult` local
 * base). Selection still resolves by parameter index, and generated locals
 * are renamed until free.
 */
interface ReservedNamesRepository {

    @Log(
        action = TaskActions.RESERVED_SAVED,
        entityId = "iloggerResult.id",
        metadata = [LogMeta("iloggerResult.title", name = "reservedTitle")],
    )
    suspend fun save(result: Task, iloggerResult: Task): Long

    @Log(action = TaskActions.RESERVED_LOGIN)
    fun login(context: Task, actor: String): Boolean

    @Log(action = TaskActions.RESERVED_FAILED, onFailure = true)
    suspend fun fail(logger: String, iloggerDelegate: String, error: Throwable?, task: Task): Boolean
}

/**
 * Class instrumentation (G-17): the generated subclass wrapper overrides open
 * members and delegates via `super`. Abstract members cannot be wrapped (the
 * subclass has no implementation to call) and must not carry @Log here.
 */
abstract class AuditedTaskBase {

    @Log(action = TaskActions.BASE_STORED, metadata = [LogMeta("task.title")])
    open suspend fun store(task: Task): Long = task.id

    @Log(action = TaskActions.BASE_LABELED)
    open fun label(task: Task): String = "${task.id}:${task.status}"
}

/** Open (non-abstract) class with constructor forwarding: the wrapper is constructed with the count. */
open class AuditedCounter(private var count: Int) {

    @Log(action = TaskActions.COUNTED_UP, entityId = "step")
    open fun bump(step: Int): Int {
        count += step
        return count
    }
}

/**
 * Top-level function instrumentation (G-17): plain functions get an adapter
 * object (`Archive_ILogger`), extension functions a same-name extension taking
 * the logger first. Receiver properties are not addressable in expressions.
 */
@Log(action = TaskActions.ARCHIVED, entity = Task::class)
suspend fun archive(task: Task): Long = task.id

@Log(action = TaskActions.RENAMED, entity = Task::class)
fun rename(task: Task, title: String): String = title

@Log(action = TaskActions.TAG_COUNTED, entity = Task::class, entityId = "result")
fun Task.tagCount(): Int = tags.size

/**
 * Generics (G-18): generic interfaces and generic functions generate typed
 * wrappers. Unbounded type parameters expose no properties, so id/metadata
 * selections resolve parameters, constants or extractors only.
 */
interface GenericStore<T : Any> {

    @Log(
        action = TaskActions.GENERIC_STORED,
        entityId = "id",
        metadata = [LogMeta("'stored'", name = "kind")],
    )
    suspend fun store(id: Long, item: T): Long

    @Log(action = TaskActions.GENERIC_TRANSFORMED, entityId = "id")
    fun <R : Any> transform(id: Long, mapper: (T) -> R): R
}

/**
 * Result and nullable returns (G-18): entity/id inference and result chains
 * unwrap `Result<T>` and nullable wrappers.
 */
interface ResultReturningRepo {

    @Log(action = TaskActions.WRAPPED_SAVED, entityId = "result.id")
    suspend fun saveWrapped(task: Task): Result<Task>

    @Log(action = TaskActions.NULLABLE_SAVED, entityId = "result.id")
    suspend fun saveNullable(task: Task): Task?

    @Log(action = TaskActions.REGISTERED)
    suspend fun register(label: String): Result<Long>
}

/**
 * Flow instrumentation (G-18): start + terminal events only; there is no
 * single result value, so only parameters/constants feed the events.
 */
interface FlowingRepo {

    @Log(
        action = TaskActions.OBSERVED,
        entityId = "taskId",
        metadata = [LogMeta("context.requestId")],
    )
    fun observe(taskId: Long): Flow<Task>

    @Log(action = TaskActions.WATCHED, entityId = "'all'", before = true, onFailure = true)
    fun watchAll(): Flow<Task>
}

/**
 * Expanded safe value model (G-20): value classes, Char, unsigned integers,
 * UUID, Instant, Duration, BigDecimal, arrays and one-level maps compile as
 * metadata sources; annotation-named ValueAdapters convert what the default
 * model rejects or reshape what it accepts.
 */
class ValueTask(
    val id: Long,
    val label: Char,
    val attempts: UInt,
    val reference: UUID,
    val createdAt: Instant,
    val deadline: Duration,
    val cost: BigDecimal,
    val flags: IntArray,
    val attributes: Map<String, String>,
    val tags: List<String>,
)

/** Value class over a scalar: accepted by the default model, rendered by toString. */
@JvmInline
value class TaskScore(val points: Int)

/** Renders a value class to its underlying scalar instead of toString. */
class ScoreAdapter : ValueAdapter<TaskScore> {
    override fun render(value: TaskScore): Any? = value.points
}

/** Renders a non-loggable whole-object selection through an adapter. */
class ValueTaskAdapter : ValueAdapter<ValueTask> {
    override fun render(value: ValueTask): Any? = value.id.toString()
}

/** Joins a list into a single string — the adapter path for container reshaping. */
class TagsAdapter : ValueAdapter<List<String>> {
    override fun render(value: List<String>): Any? = value.joinToString("|")
}

interface ValueModelRepo {

    @Log(
        action = TaskActions.SCORED,
        entityId = "task.id",
        metadata = [
            LogMeta("score", adapter = ScoreAdapter::class),
            LogMeta("task", adapter = ValueTaskAdapter::class, name = "taskRef"),
            LogMeta("task.tags", adapter = TagsAdapter::class),
            LogMeta("task.createdAt"),
            LogMeta("task.deadline"),
            LogMeta("task.reference"),
            LogMeta("task.cost"),
            LogMeta("task.flags"),
            LogMeta("task.attributes"),
            LogMeta("task.label"),
            LogMeta("task.attempts"),
        ],
    )
    suspend fun score(task: ValueTask, score: TaskScore): Int = score.points
}

/**
 * Class-level defaults (G-21): `@LogConfig` fills the fields a member leaves
 * at its default; explicit member values win; `useClassDefaults = false`
 * keeps AUDIT under a non-AUDIT config; `@NoLog` excludes a member entirely.
 */
@LogConfig(category = "config", level = LogLevel.INFO)
interface ConfiguredRepo {

    @Log(action = TaskActions.CONFIG_STORED, entityId = "task.id")
    suspend fun store(task: Task): Long = task.id

    @Log(action = TaskActions.CONFIG_WARNED, level = LogLevel.WARN, entityId = "task.id")
    suspend fun warn(task: Task): Long = task.id

    @Log(action = TaskActions.CONFIG_AUDITED, useClassDefaults = false, entityId = "task.id")
    suspend fun audit(task: Task): Long = task.id

    @Log(action = "config.never", entityId = "task.id")
    @NoLog
    suspend fun silent(task: Task): Long = task.id
}

/** Domain type with a sensitive field for the class-sensitive policy fixture. */
class PrivateTask(
    @LogSensitive val id: Long,
    val title: String,
)

/**
 * Class-sensitive policy (G-21): `@LogConfig(sensitive = MASK)` upgrades
 * policy-less metadata selections of sensitive values to masking; selections
 * of non-sensitive values log normally.
 */
@LogConfig(sensitive = SensitiveHandling.MASK)
interface MaskingConfigRepo {

    @Log(
        action = TaskActions.CONFIG_MASKED,
        entityId = "'fixed'",
        metadata = [LogMeta("task.title"), LogMeta("task.id", name = "ownerId")],
    )
    suspend fun reveal(task: PrivateTask): Long = task.id
}

/**
 * Two independent entry points emitting the same action with an identical
 * schema: the manifest folds both sources into one action entry.
 */
interface PrimaryAudit {
    @Log(action = TaskActions.SHARED_AUDIT, entity = Task::class)
    suspend fun record(task: Task)
}

interface SecondaryAudit {
    @Log(action = TaskActions.SHARED_AUDIT, entity = Task::class)
    suspend fun record(task: Task)
}

/**
 * Parameter-, type- and entry-level sensitivity (G-23): a sensitive
 * parameter is masked by an explicit policy, an explicitly OMIT-ed entry
 * produces no key at all, and a container-wide OMIT drops sensitive
 * selections while keeping the rest.
 */
interface SensitiveParamsRepo {

    @Log(
        action = TaskActions.PARAM_MASKED,
        entityId = "'fixed'",
        metadata = [
            LogMeta("token", name = "token", sensitive = SensitiveHandling.MASK),
            LogMeta("title"),
        ],
    )
    suspend fun record(@LogSensitive token: String, title: String): Long

    @Log(
        action = TaskActions.OMIT_META,
        entityId = "'fixed'",
        metadata = [
            LogMeta("task.id", name = "ownerId", sensitive = SensitiveHandling.OMIT),
            LogMeta("task.title", name = "title"),
        ],
    )
    suspend fun touch(task: PrivateTask): Long
}

/** Container-wide OMIT: policy-less sensitive selections never emit a key. */
@LogConfig(sensitive = SensitiveHandling.OMIT)
interface OmitConfigRepo {

    @Log(
        action = TaskActions.OMIT_CONFIG,
        entityId = "'fixed'",
        metadata = [LogMeta("task.id", name = "ownerId"), LogMeta("task.title", name = "title")],
    )
    suspend fun reveal(task: PrivateTask): Long
}

/** A sensitive actor override is sanitized by the container MASK policy. */
@LogConfig(sensitive = SensitiveHandling.MASK)
interface MaskedActorRepo {

    @Log(action = TaskActions.ACTOR_MASKED, actor = "email", entityId = "'fixed'")
    suspend fun act(@LogSensitive email: String): Long
}

/** A sensitive actor override is dropped by the container OMIT policy. */
@LogConfig(sensitive = SensitiveHandling.OMIT)
interface OmittedActorRepo {

    @Log(
        action = TaskActions.ACTOR_OMITTED,
        actor = "email",
        entityId = "'fixed'",
        metadata = [LogMeta("title")],
    )
    suspend fun act(@LogSensitive email: String, title: String): Long
}

/** A type-use annotation on the return type marks the selected result. */
interface ReturnSensitiveRepo {

    @Log(
        action = TaskActions.RETURN_MASKED,
        entityId = "'fixed'",
        metadata = [LogMeta("result", name = "token", sensitive = SensitiveHandling.MASK)],
    )
    fun issue(): @LogSensitive String
}

/**
 * Extractor DI (G-24): the constructor needs application state, so it has no
 * no-argument fallback and must be registered on the logger.
 */
class InjectedSummaryExtractor(private val prefix: String) : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = "$prefix:${value.title}"
}

/** An object extractor resolves through the generated object fallback. */
object ObjectSummaryExtractor : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = "object:${value.id}"
}

/** Runtime validation: returning a non-loggable object omits the entry. */
class UnsafeSummaryExtractor : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = value
}

/** Runtime validation: a throwing extractor omits the entry instead of failing the call. */
class ThrowingSummaryExtractor : LogValueExtractor<Task> {
    override fun extract(value: Task): Any? = error("extractor exploded")
}

/**
 * Extractor resolution paths: registered factory (inject = true), generated
 * object fallback, and always-on output validation for unsafe/throwing output.
 */
interface ExtractorRepo {

    @Log(
        action = TaskActions.INJECTED_SUMMARY,
        entityId = "'fixed'",
        metadata = [
            LogMeta("task", extractor = InjectedSummaryExtractor::class, inject = true, name = "summary"),
        ],
    )
    suspend fun injected(task: Task): Int

    @Log(
        action = TaskActions.OBJECT_SUMMARY,
        entityId = "'fixed'",
        metadata = [LogMeta("task", extractor = ObjectSummaryExtractor::class, name = "summary")],
    )
    suspend fun asObject(task: Task): Int

    @Log(
        action = TaskActions.UNSAFE_SUMMARY,
        entityId = "'fixed'",
        metadata = [LogMeta("task", extractor = UnsafeSummaryExtractor::class, name = "summary")],
    )
    suspend fun unsafe(task: Task): Int

    @Log(
        action = TaskActions.THROWING_SUMMARY,
        entityId = "'fixed'",
        metadata = [
            LogMeta("task", extractor = ThrowingSummaryExtractor::class, name = "summary"),
            LogMeta("task.title"),
        ],
    )
    suspend fun throwing(task: Task): Int
}
