package io.ilogger.core

import io.ilogger.annotations.LogLevel
import java.util.Collections

/**
 * The stable, immutable representation of one log or audit event.
 *
 * Events are the unit of ILogger: generated instrumentation, the manual API,
 * sinks and tests all speak in [LogEvent]s. Instances are immutable: the
 * [metadata] map and [changes] list handed to the constructor are copied into
 * unmodifiable containers, so later mutation of the caller's collections can
 * never change an event that is already in flight or persisted.
 *
 * Use [LogEventDraft] with `ILogger.record(draft)` for a builder-style API
 * where every field except the action has a default.
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
public class LogEvent(
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

    metadata: Map<String, Any?> = emptyMap(),

    changes: List<FieldChange> = emptyList(),

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
    /** Explicitly selected metadata; only loggable values appear (see class docs). */
    public val metadata: Map<String, Any?> = Collections.unmodifiableMap(LinkedHashMap(metadata))

    /** Explicit before→after comparisons; empty by default. */
    public val changes: List<FieldChange> = Collections.unmodifiableList(ArrayList(changes))

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

    public fun copy(
        id: String = this.id,
        timestampMillis: Long = this.timestampMillis,
        level: LogLevel = this.level,
        action: String = this.action,
        actionVersion: Int = this.actionVersion,
        actor: Actor? = this.actor,
        entity: String? = this.entity,
        entityId: String? = this.entityId,
        metadata: Map<String, Any?> = this.metadata,
        changes: List<FieldChange> = this.changes,
        source: String? = this.source,
        category: String? = this.category,
        correlationId: String? = this.correlationId,
        causationId: String? = this.causationId,
        outcome: Outcome = this.outcome,
        failure: LogFailure? = this.failure,
    ): LogEvent = LogEvent(
        id = id,
        timestampMillis = timestampMillis,
        level = level,
        action = action,
        actionVersion = actionVersion,
        actor = actor,
        entity = entity,
        entityId = entityId,
        metadata = metadata,
        changes = changes,
        source = source,
        category = category,
        correlationId = correlationId,
        causationId = causationId,
        outcome = outcome,
        failure = failure,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogEvent) return false
        return id == other.id &&
            timestampMillis == other.timestampMillis &&
            level == other.level &&
            action == other.action &&
            actionVersion == other.actionVersion &&
            actor == other.actor &&
            entity == other.entity &&
            entityId == other.entityId &&
            metadata == other.metadata &&
            changes == other.changes &&
            source == other.source &&
            category == other.category &&
            correlationId == other.correlationId &&
            causationId == other.causationId &&
            outcome == other.outcome &&
            failure == other.failure
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestampMillis.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + action.hashCode()
        result = 31 * result + actionVersion
        result = 31 * result + (actor?.hashCode() ?: 0)
        result = 31 * result + (entity?.hashCode() ?: 0)
        result = 31 * result + (entityId?.hashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        result = 31 * result + changes.hashCode()
        result = 31 * result + (source?.hashCode() ?: 0)
        result = 31 * result + (category?.hashCode() ?: 0)
        result = 31 * result + (correlationId?.hashCode() ?: 0)
        result = 31 * result + (causationId?.hashCode() ?: 0)
        result = 31 * result + outcome.hashCode()
        result = 31 * result + (failure?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = buildString(128) {
        append("LogEvent(id=").append(id)
        append(", timestampMillis=").append(timestampMillis)
        append(", level=").append(level)
        append(", action=").append(action)
        append(", actionVersion=").append(actionVersion)
        append(", actor=").append(actor)
        append(", entity=").append(entity)
        append(", entityId=").append(entityId)
        append(", metadata=").append(metadata)
        append(", changes=").append(changes)
        append(", source=").append(source)
        append(", category=").append(category)
        append(", correlationId=").append(correlationId)
        append(", causationId=").append(causationId)
        append(", outcome=").append(outcome)
        append(", failure=").append(failure)
        append(')')
    }
}
