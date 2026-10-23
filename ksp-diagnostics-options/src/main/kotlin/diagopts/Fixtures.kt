package diagopts

import io.ilogger.annotations.Log

data class Task(val id: Long, val title: String)

/**
 * `ilogger.require-actor = true`: AUDIT operations without an explicit actor
 * depend on ambient attribution, which is not deterministic for tests or
 * incident review.
 */
interface MissingAuditActor {
    // EXPECTED: has no explicit actor and depends on the ambient
    @Log(action = "task.created", entity = Task::class, category = "audit")
    suspend fun create(task: Task): Long
}

/**
 * `ilogger.sink-categories = "audit,security"`: a category outside the list
 * has no sink binding, so the event would be dropped silently.
 */
interface UnboundCategory {
    // EXPECTED: Category 'billing' used by
    @Log(action = "task.invoiced", entity = Task::class, actor = "requester", category = "billing")
    suspend fun invoice(requester: String, task: Task): Long
}

/**
 * Compliant: explicit actor and a bound category. Both opt-in rules stay
 * silent.
 */
interface DeterministicAudit {
    @Log(action = "task.archived", entity = Task::class, actor = "requester", category = "audit")
    suspend fun archive(requester: String, task: Task): Long

    @Log(action = "task.locked", entityId = "'fixed'", actor = "requester", category = "security")
    suspend fun lock(requester: String)
}
