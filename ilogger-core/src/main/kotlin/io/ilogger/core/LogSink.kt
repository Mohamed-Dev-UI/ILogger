package io.ilogger.core

/**
 * A destination for [LogEvent]s.
 *
 * Sinks are pull-through writers: the dispatch engine hands them immutable
 * events. Sinks may batch ([writeAll]), buffer, serialize, forward, retry, or
 * ignore. They must be safe for concurrent use when shared between dispatch
 * paths — the engine serializes writes per binding, but applications may
 * attach the same sink to several loggers.
 *
 * ## Guarantees by implementation
 *
 * Sink durability is implementation-specific:
 * - an in-memory sink guarantees nothing across process death;
 * - a file/database sink can survive restarts;
 * - a remote sink can survive network failures via its own buffering.
 *
 * Consult an implementation's documentation for its exact guarantees.
 *
 * ## Error propagation
 *
 * Throwing from [write] is allowed; the configured [ErrorMode] decides what
 * happens. Sinks should not log via ILogger inside [write] (recursion is
 * detected and dropped).
 */
public interface LogSink : AutoCloseable {
    /** Writes a single event. */
    public suspend fun write(event: LogEvent)

    /** Writes a batch. Default: sequential [write] calls. */
    public suspend fun writeAll(events: List<LogEvent>) {
        for (event in events) write(event)
    }

    /** Flushes internal buffers. Default: no-op. */
    public suspend fun flush() {}

    /** Releases resources. Default: no-op. */
    override fun close() {}
}
