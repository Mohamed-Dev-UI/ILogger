package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel

/**
 * Builder-style description of a manual event, accepted by
 * [ILogger.record], [ILogger.recordNow] and [ILogger.recordBlocking].
 *
 * Only [action] is required; every other field defaults to the same value as
 * the corresponding [LogEvent] constructor parameter. Fields that the logger
 * owns — event id, timestamp, correlation/causation ids — are not part of the
 * draft and are resolved from the configured generators and ambient
 * [LogContext] exactly as for the parameter-based overloads.
 *
 * [dispatch] and [sampling] are recording hints: they are not stored on the
 * resulting event. Drafts are immutable snapshots; mutate a `copy` instead.
 */
public data class LogEventDraft(
    /** Canonical action code, e.g. `"task.created"`. Must not be blank. */
    public val action: String,
    public val level: LogLevel = LogLevel.AUDIT,
    public val entity: String? = null,
    public val entityId: String? = null,
    public val actor: Actor? = null,
    public val outcome: Outcome = Outcome.SUCCESS,
    public val failure: Throwable? = null,
    public val metadata: Map<String, Any?> = emptyMap(),
    public val changes: List<FieldChange> = emptyList(),
    public val source: String? = null,
    public val category: String? = null,
    public val actionVersion: Int = 1,
    /** Dispatch mode; [DispatchMode.DEFAULT] uses the logger configuration. */
    public val dispatch: DispatchMode = DispatchMode.DEFAULT,
    /** Per-call sampling rate in `0.0..1.0`; `-1.0` (default) uses the sampler. */
    public val sampling: Double = -1.0,
) {
    init {
        require(action.isNotBlank()) { "LogEventDraft action must not be blank" }
        require(actionVersion >= 1) { "LogEventDraft actionVersion must be >= 1" }
    }
}
