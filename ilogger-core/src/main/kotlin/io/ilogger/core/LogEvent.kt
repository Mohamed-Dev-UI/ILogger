package io.ilogger.core

import io.ilogger.annotations.LogLevel

/**
 * The stable, immutable representation of one log or audit event.
 *
 * Events are the unit of ILogger: generated instrumentation, the manual API,
 * sinks and tests all speak in [LogEvent]s. Instances are immutable; metadata
 * maps and change lists handed to the constructor must not be mutated
 * afterwards (builders in this library always produce immutable containers).
 *
 * ## Concept layout
 *
 * - **Identity** — [id] (event), [LogEvent.actor] (actor id), [entityId].
 * - **Semantics** — [action] (+ [actionVersion]), [entity].
 * - **Context** — [timestampMillis], [source], [correlationId], [causationId].
 * - **Payload** — [metadata] (explicitly selected, log-safe values only),
 *   [changes] (explicitly provided before/after pairs).
 *
 * ## Identity guarantees
 *
 * [id] is unique per event, minted by the configured [EventIdGenerator]. It is
 * stable across persistence, retries and synchronization: receivers
 * deduplicate by id. Timestamps alone are never identity.
 *
 * ## Privacy
 *
 * Consumers may expect that [metadata] values are log-safe primitives
 * (numbers, booleans, strings, enums-as-names or null). Domain objects are
 * never included unless an application explicitly registers/invokes an
 * extractor.
 */
public data class LogEvent(
    /** Event id; unique within a deployment. Blanks are rejected. */
    public val id: String,

    /** Epoch milliseconds at event creation (from the configured [LogClock]). */
    public val timestampMillis: Long,

    /** Diagnostic or audit level (see [LogLevel]). */
    public val level: LogLevel,

    /** Canonical action code, e.g. `"task.created"`. */
    public val action: String,

    /** Payload schema version of this action (see `@Log` docs). */
    public val actionVersion: Int = 1,

    /** Resolved actor, when known. Null means "no actor could be proven". */
    public val actor: Actor? = null,

    /** Entity type name e.g. `"Task"`. Not a Kotlin class reference for stability. */
    public val entity: String? = null,

    /** Entity id as string; null when it could not be determined. */
    public val entityId: String? = null,

    /** Explicitly selected metadata; only loggable values appear (see class docs). */
    public val metadata: Map<String, Any?> = emptyMap(),

    /** Explicit before→after comparisons; empty by default. */
    public val changes: List<FieldChange> = emptyList(),

    /** Logical origin e.g. `"TaskDao.insert"`; used for routing/dedup. */
    public val source: String? = null,

    /** Routing category (matches [io.ilogger.core.sink.SinkBinding]); null = default. */
    public val category: String? = null,

    /** Correlation id from the ambient [LogContext]. */
    public val correlationId: String? = null,

    /** Causation id from the ambient [LogContext]. */
    public val causationId: String? = null,

    /** Operation phase ([Outcome.SUCCESS] by default). */
    public val outcome: Outcome = Outcome.SUCCESS,

    /** Failure details when [outcome] == `FAILURE`. */
    public val failure: LogFailure? = null,
) {
    init {
        require(id.isNotBlank()) { "LogEvent id must not be blank" }
        require(action.isNotBlank()) { "LogEvent action must not be blank" }
        require(actionVersion >= 1) { "LogEvent actionVersion must be >= 1" }
    }

    /**
     * Stable semantic identifier including schema version
     * (e.g. `"task.created"` or `"task.created@v2"`). Remains meaningful even
     * if the underlying Kotlin classes change between library versions.
     */
    public val schemaId: String
        get() = if (actionVersion <= 1) action else "$action@v$actionVersion"
}
