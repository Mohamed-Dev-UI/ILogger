package io.ilogger.annotations

/**
 * Controls automatic before→after capture for [Log] operations.
 *
 * Automatic capture diffs every eligible property of the operation's entity
 * (public loggable values that are neither [LogSensitive], [LogExclude] nor
 * riskily named) and records only the fields that actually changed. The
 * before/after objects are inferred from the signature:
 *
 * - two parameters of the same entity type (the first is "before", the second
 *   is "after"; the event's entity id is inferred from the second), or
 * - one entity parameter and an entity-typed return value.
 *
 * No database read is performed; the caller must supply both object states.
 * Unsupported signatures produce a compile error when [ALL] is set explicitly,
 * and are skipped when [ALL] comes from the module-wide default.
 */
public enum class AutoChanges {
    /** Use the module-wide `ilogger.auto-changes` / `ilogger { autoChanges }` setting. */
    DEFAULT,

    /** Capture only explicit [LogChange] entries. */
    OFF,

    /** Capture every eligible changed field, in addition to explicit [LogChange] entries. */
    ALL,
}
