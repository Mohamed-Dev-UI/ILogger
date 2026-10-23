package io.ilogger.annotations

/**
 * Marks an object as the canonical action registry of its module and lists
 * the action codes it owns.
 *
 * The processor validates the codes, generates `<ObjectName>_ILoggerActions`
 * (sorted `actions` list) and folds them into the module's machine-readable
 * schema manifest (`META-INF/ilogger/schema.json`). When a module declares at
 * least one registry, every `@Log` action used in that module must be
 * declared by one — undeclared actions produce a warning, the same
 * action+version used with two schema shapes is an error.
 *
 * ```kotlin
 * @LogActions(TaskActions.CREATED, TaskActions.UPDATED)
 * object TaskActions {
 *     const val CREATED = "task.created"
 *     const val UPDATED = "task.updated"
 * }
 * ```
 *
 * Codes are listed as annotation arguments because KSP cannot read property
 * initializers; referencing the same constants keeps one source of truth.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class LogActions(
    vararg val actions: String,
)
