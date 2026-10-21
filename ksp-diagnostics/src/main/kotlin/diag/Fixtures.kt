package diag

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogDao
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
    // EXPECTED: @Log(dispatch = INLINE) cannot instrument non-suspend functions
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
