package diag

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogActions
import io.ilogger.annotations.LogChange
import io.ilogger.annotations.LogConfig
import io.ilogger.annotations.LogDao
import io.ilogger.annotations.LogExclude
import io.ilogger.annotations.LogLevel
import io.ilogger.annotations.LogMeta
import io.ilogger.annotations.LogSensitive

data class Row(val id: Long, val title: String)

/**
 * @LogDao is an interface-level opt-in; a class/object cannot be a DAO.
 */
// EXPECTED: @LogDao can only annotate an interface.
@LogDao
class NotADao

/**
 * Hilt bindings are optional and require Hilt on the consumer classpath.
 */
// EXPECTED: @LogDao(hilt = true) requires Hilt on the compilation classpath.
@LogDao(hilt = true)
interface HiltWithoutHilt {
    @Log(action = "task.created", entity = Row::class)
    suspend fun create(row: Row): Long
}

/**
 * Koin bindings are optional and require Koin on the consumer classpath.
 */
// EXPECTED: @LogDao(koin = true) requires Koin on the compilation classpath.
@LogDao(koin = true)
interface KoinWithoutKoin {
    @Log(action = "task.created", entity = Row::class)
    suspend fun create(row: Row): Long
}


data class Customer(val id: Long, val creditScore: Int, @LogSensitive val tierName: String)

/**
 * Unknown source in LogMeta: `task` is not a parameter (only `title` exists).
 */
interface UnknownSource {
    // EXPECTED: unknown source 'task'. Known sources:
    @Log(action = "task.updated", entity = Row::class, metadata = [LogMeta("task.title")])
    suspend fun touch(title: String): Int
}

/**
 * No provable entity id: no domain param, no id-shaped result, no id param.
 */
interface NoProvableId {
    // EXPECTED: Cannot infer entity ID of 'explode'. No provable source:
    @Log(action = "task.failed")
    suspend fun explode()
}

/**
 * INLINE dispatch on a non-suspend function is a dead combination.
 */
interface InlineNonSuspend {
    // EXPECTED: @Log(dispatch = INLINE) cannot instrument non-suspend, non-flow functions
    @Log(action = "task.closed", dispatch = DispatchMode.INLINE)
    fun quick(id: Long)
}

/**
 * Audit events must never be sampled.
 */
interface AuditSampling {
    // EXPECTED: Audit events must not be sampled
    @Log(action = "task.created", sampling = 0.5)
    suspend fun create(task: Row): Long
}

/**
 * Selecting a @LogSensitive property without an explicit policy is a DENY.
 */
interface SensitiveDeny {
    // EXPECTED: without an explicit policy
    @Log(action = "customer.updated", entity = Customer::class, metadata = [LogMeta("c.tierName")])
    suspend fun update(c: Customer)
}

/**
 * Chained access into a vararg parameter is rejected: whole value + extractor only.
 */
interface VarargChain {
    // EXPECTED: cannot provide chained values
    @Log(action = "task.batch.created", metadata = [LogMeta("tasks.first().title")])
    suspend fun insertAll(vararg tasks: Row): List<Long>
}

/**
 * @Log on a member of a final class: KSP instruments classes by subclassing,
 * and final classes can be neither subclassed nor proxied. The planned
 * compiler plugin (G-17b) will lift this.
 */
class FinalAudit {
    // EXPECTED: final class
    @Log(action = "task.created")
    suspend fun create(row: Row): Long = row.id
}

/**
 * @Log on an abstract member: the generated subclass wrapper has no
 * implementation to delegate to.
 */
abstract class AbstractAudit {
    // EXPECTED: abstract member
    @Log(action = "task.deleted")
    abstract suspend fun delete(row: Row)
}

/**
 * A @LogSensitive parameter selected as metadata without a policy is denied.
 */
interface ParamSensitiveDeny {
    // EXPECTED: without an explicit policy
    @Log(action = "sensitive.param", entityId = "'fixed'", metadata = [LogMeta("token")])
    suspend fun touch(@LogSensitive token: String)
}

/**
 * Entity ids must never come from sensitive parameters, whatever the policy.
 */
interface ParamSensitiveId {
    // EXPECTED: entity ids must never be masked or hashed
    @Log(action = "sensitive.id", entityId = "token")
    suspend fun lookup(@LogSensitive token: String)
}

/**
 * Change tracking refuses sensitive sources as well.
 */
interface SensitiveChange {
    // EXPECTED: LogChange('customer.tierName') selects value of 'tierName'
    @Log(
        action = "sensitive.change",
        entityId = "'fixed'",
        changes = [LogChange(field = "tier", from = "customer.tierName", to = "newTier")],
    )
    suspend fun retier(customer: Customer, newTier: String)
}

/**
 * A type-use annotation on the return type marks the whole result.
 */
interface ReturnSensitiveDeny {
    // EXPECTED: selects the return value marked @LogSensitive
    @Log(action = "sensitive.result", entityId = "'fixed'", metadata = [LogMeta("result")])
    fun issue(): @LogSensitive String
}

/**
 * @LogExclude on a parameter forbids every reference, even with a policy.
 */
interface ExcludedParam {
    // EXPECTED: Parameter 'raw' is @LogExclude
    @Log(action = "excluded.param", entityId = "'fixed'", metadata = [LogMeta("raw")])
    suspend fun login(@LogExclude raw: String)
}

/**
 * The audit-sampling rule evaluates the RESOLVED level/sampling: a method
 * inheriting both from @LogConfig hits the same error as a method-level
 * annotation.
 */
// EXPECTED: Audit events must not be sampled
@LogConfig(level = LogLevel.AUDIT, sampling = 0.5)
interface ConfigAuditSampling {
    @Log(action = "task.created", entity = Row::class)
    suspend fun create(row: Row): Long
}

/**
 * Action codes are dot-separated; a single-segment code never routes by
 * domain and is rejected by dashboards.
 */
// EXPECTED: has a single segment
@LogActions("shortcut")
object SingleSegmentActions

/**
 * Only an object can act as an action registry: it exists to hold `const val`
 * codes, which classes and interfaces do not provide.
 */
// EXPECTED: @LogActions can only annotate an object
@LogActions("dup.shared")
class ActionsOnClass

/**
 * Two operations reuse one action+version with different payloads: consumers
 * would read one schemaId with two shapes.
 */
interface DuplicateSchemaFirst {
    @Log(action = "dup.shared", entityId = "id")
    suspend fun first(id: Long, title: String)
}

interface DuplicateSchemaSecond {
    // EXPECTED: already used with a different schema
    @Log(action = "dup.shared", entityId = "id", metadata = [LogMeta("title")])
    suspend fun second(id: Long, title: String)
}

/**
 * A version bump with an unchanged payload is noise, not a contract change.
 */
interface VersionBumpFirst {
    @Log(action = "versioned.same", schemaVersion = 1, entityId = "id")
    suspend fun first(id: Long)
}

interface VersionBumpSecond {
    // EXPECTED: identical schema
    @Log(action = "versioned.same", schemaVersion = 2, entityId = "id")
    suspend fun second(id: Long)
}

/**
 * DI extractors (G-24): a constructor that needs application state has no
 * generated fallback, so the selection must opt into registration.
 */
class NeedsDependencies(private val config: String) : io.ilogger.core.LogValueExtractor<Row> {
    override fun extract(value: Row): Any? = "$config:${value.title}"
}

interface ExtractorWithoutInject {
    // EXPECTED: needs a public no-argument constructor
    @Log(
        action = "task.diExtractor",
        entity = Row::class,
        metadata = [LogMeta("row", extractor = NeedsDependencies::class, name = "summary")],
    )
    suspend fun touch(row: Row): Long
}

interface ExtractorInjectWithoutExtractor {
    // EXPECTED: inject = true) requires an extractor
    @Log(
        action = "task.injectMiss",
        entity = Row::class,
        metadata = [LogMeta("row.title", inject = true)],
    )
    suspend fun touch(row: Row): Long
}
