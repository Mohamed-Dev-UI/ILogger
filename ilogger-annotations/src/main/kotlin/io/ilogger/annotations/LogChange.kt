package io.ilogger.annotations

/**
 * Declares one before→after field comparison to record in a [Log] event.
 *
 * ILogger never queries a database before an update to compute differences
 * (that would double the cost of every write). Change tracking is therefore
 * explicit: the caller supplies both values via source expressions when the
 * information is actually available.
 *
 * ```kotlin
 * @Log(
 *     action = TaskActions.UPDATED,
 *     changes = [
 *         LogChange(field = "status", from = "oldTask.status", to = "task.status"),
 *     ],
 * )
 * suspend fun update(oldTask: Task, task: Task)
 * ```
 *
 * The generated event contains `changes = [status: TODO → DONE]` shaped as
 * [io.ilogger.core.FieldChange] values.
 *
 * @param field Event field name, e.g. `"status"` or `"priority"`.
 * @param from Source expression for the value before the operation.
 * @param to Source expression for the value after the operation.
 */
public annotation class LogChange(
    public val field: String,
    public val from: String,
    public val to: String = "",
)
