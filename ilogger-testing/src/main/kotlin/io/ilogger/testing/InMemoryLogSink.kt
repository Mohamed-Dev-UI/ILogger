package io.ilogger.testing

import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogEvent

/**
 * Thread-safe in-memory [LogSink] that captures every handed [LogEvent]
 * without dropping or transforming it.
 *
 * This is the primitive behind [TestLogSink]; use it directly when you need a
 * sink without the rest of [TestKit], or extend it to add destination-specific
 * behaviour. The capture list is never cleared implicitly, so tests observe
 * exactly what the engine delivered.
 *
 * `writeDirect` records on the caller thread, making the sink usable as an
 * INLINE diagnostic fast path. [awaitCount] polls the real clock so async
 * workers on real dispatchers are observable; prefer [TestKit.drain] under
 * `runTest` virtual time.
 */
public open class InMemoryLogSink : DirectLogSink {

    private val lock = Any()
    private val captured = ArrayList<LogEvent>()

    /** Immutable snapshot of all captured events, in arrival order. */
    public fun events(): List<LogEvent> = synchronized(lock) { captured.toList() }

    /** Most recently captured event, or null when nothing was captured yet. */
    public fun last(): LogEvent? = synchronized(lock) { captured.lastOrNull() }

    /** Number of captured events so far. */
    public fun count(): Int = synchronized(lock) { captured.size }

    /** Discards captured events (keeps the sink usable). */
    public fun clear(): Unit {
        synchronized(lock) { captured.clear() }
    }

    override suspend fun write(event: LogEvent): Unit = writeDirect(event)

    override fun writeDirect(event: LogEvent) {
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
