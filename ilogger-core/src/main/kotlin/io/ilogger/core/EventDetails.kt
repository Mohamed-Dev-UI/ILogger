package io.ilogger.core

import io.ilogger.annotations.LogLevel
import kotlin.coroutines.cancellation.CancellationException

/**
 * What phase of an operation an event describes.
 */
public enum class Outcome {
    /** Emitted before the operation runs ([io.ilogger.annotations.Log.before]). */
    START,

    /** The operation completed normally. */
    SUCCESS,

    /** The operation threw; original exception is untouched. */
    FAILURE,
}

/**
 * One recorded before→after comparison recorded by
 * [io.ilogger.annotations.LogChange] selection.
 */
public class FieldChange(
    public val field: String,
    public val before: Any?,
    public val after: Any?,
) {
    override fun equals(other: Any?): Boolean =
        other is FieldChange && other.field == field && other.before == before && other.after == after

    override fun hashCode(): Int {
        var result = field.hashCode()
        result = 31 * result + (before?.hashCode() ?: 0)
        result = 31 * result + (after?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "$field: $before → $after"
}

/**
 * Structured description of a failure ([io.ilogger.core.LogEvent.outcome] ==
 * `FAILURE`). Captured eagerly at event creation: retained stack frames are
 * limited and contain only class-level information without local variables.
 */
public class LogFailure(
    public val errorType: String,
    public val message: String?,
    public val rootCauseType: String?,
    public val stackSummary: List<String>,
    /**
     * `true` when the failure was a coroutine cancellation rather than a
     * business error. Cancellation events are only produced when generated
     * instrumentation opts in with `@Log(onCancellation = LOG)`.
     */
    public val cancelled: Boolean = false,
) {
    /** Alias for [cancelled], readable as a predicate. */
    public val isCancellation: Boolean
        get() = cancelled

    public companion object {
        public const val DEFAULT_STACK_FRAMES: Int = 8

        public fun from(error: Throwable, maxStackFrames: Int = DEFAULT_STACK_FRAMES): LogFailure {
            var root: Throwable = error
            while (root.cause != null && root.cause !== root) root = root.cause as Throwable
            return LogFailure(
                errorType = error::class.java.name,
                message = error.message,
                rootCauseType = if (root !== error) root::class.java.name else null,
                stackSummary = error.stackTrace.take(maxStackFrames).map { it.toString() },
                cancelled = error is CancellationException,
            )
        }
    }

    override fun toString(): String = "$errorType(message=$message)"
}
