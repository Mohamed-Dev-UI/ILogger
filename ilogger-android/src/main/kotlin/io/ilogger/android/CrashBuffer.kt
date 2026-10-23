package io.ilogger.android

import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogEvent

/**
 * Bounded in-memory ring of the most recent events, used as the crash handoff
 * source.
 *
 * Attach it as a sink (usually catch-all). [CrashHandoff] snapshots the ring
 * when the process is about to die and persists it next to the crash record.
 * The capacity bounds memory use; older events are evicted first.
 *
 * The sink is lock-protected and safe for concurrent writers. It is a
 * [DirectLogSink] so inline diagnostics are captured immediately.
 */
public class CrashBuffer(public val capacity: Int = DEFAULT_CAPACITY) : DirectLogSink {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val events = ArrayDeque<LogEvent>(capacity)

    override suspend fun write(event: LogEvent) {
        writeDirect(event)
    }

    override fun writeDirect(event: LogEvent) {
        synchronized(lock) {
            if (events.size == capacity) events.removeFirst()
            events.addLast(event)
        }
    }

    /** Events in arrival order, oldest first. */
    public fun snapshot(): List<LogEvent> = synchronized(lock) { events.toList() }

    public fun clear() {
        synchronized(lock) { events.clear() }
    }

    public val size: Int
        get() = synchronized(lock) { events.size }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 64
    }
}
