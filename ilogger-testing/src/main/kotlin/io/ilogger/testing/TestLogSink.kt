package io.ilogger.testing

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink

/**
 * In-memory [LogSink] for tests: captures every handed [LogEvent] without
 * dropping or transforming. Thread-safe for concurrent dispatch paths.
 *
 * Verification helpers ([awaitCount]) make async/buffered dispatch modes
 * deterministic in tests: they yield while waiting for expected events
 * instead of sleeping blindly.
 */
public class TestLogSink : LogSink {

    private val lock = Any()
    private val captured = ArrayList<LogEvent>()

    /** Immutable snapshot of all captured events, in arrival order. */
    public fun events(): List<LogEvent> = synchronized(lock) { captured.toList() }

    /** Most recently captured event, or null when nothing was captured yet. */
    public fun last(): LogEvent? = synchronized(lock) { captured.lastOrNull() }

    /** Number of captured events so far. */
    public fun count(): Int = synchronized(lock) { captured.size }

    /** Discards captured events (keeps the sink usable). */
    public fun clear() = synchronized(lock) { captured.clear() }

    override suspend fun write(event: LogEvent) {
        synchronized(lock) { captured.add(event) }
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        synchronized(lock) { captured.addAll(events) }
    }

    /**
     * Suspends until at least [count] events have arrived or [timeoutMillis]
     * elapsed; returns `true` when the expectation is met.
     *
     * Deliberately sleeps on the real clock (not the current delay mechanism):
     * async workers deliver in real time, including from under virtual-time
     * test runners such as `runTest`.
     */
    public suspend fun awaitCount(count: Int, timeoutMillis: Long = 5_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (count() < count) {
            if (System.nanoTime() > deadline) return count() >= count
            Thread.sleep(5)
        }
        return true
    }
}
