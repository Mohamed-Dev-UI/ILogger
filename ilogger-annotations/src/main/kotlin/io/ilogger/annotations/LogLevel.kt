package io.ilogger.annotations

/**
 * Severity / semantic level of a log event.
 *
 * Diagnostic levels ([TRACE]..[ERROR]) describe what the application is doing.
 * [AUDIT] describes *who did what to which entity* and carries stronger
 * guarantees: audit events are structured, never sampled by default, and are
 * intended for persistence and synchronization.
 *
 * Level filtering (`minLevel`, sampling) must never silently disable audit
 * events; audit filtering is configured explicitly and separately.
 */
public enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,

    /** Structured audit event (who/what/when). Strongest guarantees. */
    AUDIT,
}
