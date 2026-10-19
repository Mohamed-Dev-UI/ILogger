package io.ilogger.annotations

import kotlin.reflect.KClass

/**
 * Declares that a function's operation should produce a structured, type-safe
 * log/audit event.
 *
 * Common usage requires almost nothing:
 *
 * ```kotlin
 * @Log(TaskActions.CREATED)
 * @Insert
 * suspend fun insert(task: Task): Long
 * ```
 *
 * The KSP processor infers everything it can *prove* from the signature
 * (entity type, entity id, source location) and validates everything else at
 * compile time. Ambiguities are never guessed: they produce a compile error
 * explaining exactly how to fix them.
 *
 * ## Value sources
 *
 * Several options take a *source expression* resolved at compile time:
 *
 * | Expression         | Meaning                                              |
 * |--------------------|------------------------------------------------------|
 * | `result`           | The function's return value                          |
 * | `result.prop`      | A property of the return value                       |
 * | `paramName`        | A function parameter (values of loggable types only) |
 * | `paramName.prop`   | A property of a parameter                            |
 * | `'literal'`        | A compile-time constant                              |
 * | `actor`            | The resolved actor's id                              |
 * | `actor.name`       | Actor name/type/attribute (e.g. `actor.type`)        |
 * | `context.key`      | A value from the ambient [LogContext]                |
 *
 * ## Precedence (highest first)
 *
 * 1. Explicitly configured values in this annotation,
 * 2. Inference from the function signature (only when unambiguous),
 * 3. Compile-time validation: anything that cannot be proven is an error.
 *
 * The generated code emits the success event **after** the operation succeeds
 * and (optionally) a failure event if the operation throws — unless
 * [before] is set, which also emits a start ("attempt") event beforehand.
 *
 * @param action Canonical action code, e.g. `"task.created"`. Convention:
 *   `<domain>.<event>[.<variant>]`, lower snake_case segments. Usually
 *   referenced from a constants object:
 *   ```kotlin
 *   object TaskActions {
 *       const val CREATED = "task.created"
 *       const val UPDATED = "task.updated"
 *   }
 *   ```
 * @param schemaVersion Version of the *payload* of this event. Stored with the
 *   event so consumers can evolve interpretation over time
 *   (`action@v2`). Increment when the metadata/changes contract changes.
 * @param entity Entity type this operation acts on. If unset, the processor
 *   infers it from the signature when provable; an ambiguous signature is a
 *   compile error, not a guess.
 * @param entityId Explicit source expression for the entity id. If unset, the
 *   processor infers it (insert return value, `id` property of a single entity
 *   parameter, or the single primitive parameter). Ambiguity is a compile error.
 * @param actor Explicit source expression overriding actor resolution for this
 *   event. If unset, the ambient actor (coroutine context, then the configured
 *   [io.ilogger.core.ActorProvider] equivalent) is used — no artificial
 *   parameters are needed.
 * @param level Event level. Defaults to [LogLevel.AUDIT]; most operations are
 *   audit events. Diagnostic side-logs should use the logger API instead.
 * @param category Routing category used by the application to bind events to
 *   sinks (e.g. `"db"`, `"http"`, `"billing"`). Empty means the default route.
 * @param dispatch Dispatch mode for *this* event; [DispatchMode.DEFAULT]
 *   follows application configuration.
 * @param metadata Explicitly selected metadata ([LogMeta] values). Nothing
 *   else is captured: domain objects are never serialized wholesale.
 * @param changes Explicitly selected before/after value pairs ([LogChange]).
 *   ILogger never queries a database to compute diffs; the caller provides
 *   the "before" values if change tracking is required.
 * @param before Also emit a start event *before* the operation runs
 *   (outcome `START`). Useful for operations that may crash the process.
 * @param onSuccess Emit the success event after the operation completes
 *   (outcome `SUCCESS`).
 * @param onFailure Emit a failure event (outcome `FAILURE`) if the operation
 *   throws, without altering the thrown exception. Failure events include the
 *   exception class name and message.
 * @param sampling Optional override of the diagnostic sampling rate for this
 *   event, in `[0.0, 1.0]`. Ignored for [LogLevel.AUDIT] events (audit events
 *   are never sampled by default; sampling an audit event deliberately is a
 *   compile error).
 * @param description Free-form human documentation of the event, embedded in
 *   generated code and tooling. Not included in events.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Log(
    public val action: String,
    public val schemaVersion: Int = 1,
    public val entity: KClass<*> = Nothing::class,
    public val entityId: String = "",
    public val actor: String = "",
    public val level: LogLevel = LogLevel.AUDIT,
    public val category: String = "",
    public val dispatch: DispatchMode = DispatchMode.DEFAULT,
    public val metadata: Array<LogMeta> = [],
    public val changes: Array<LogChange> = [],
    public val before: Boolean = false,
    public val onSuccess: Boolean = true,
    public val onFailure: Boolean = false,
    public val sampling: Double = -1.0,
    public val description: String = "",
)
