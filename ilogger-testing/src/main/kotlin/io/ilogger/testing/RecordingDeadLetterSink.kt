package io.ilogger.testing

import io.ilogger.core.DeadLetterSink
import io.ilogger.core.DroppedEvent
import io.ilogger.core.DropReason
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [DeadLetterSink] for tests: records every dropped event and counts
 * flush/close calls.
 *
 * Dead-letter delivery runs on the engine's dedicated pipeline, so read after
 * `logger.closeAndReport()` (or `logger.flush()`) for a complete snapshot.
 */
public class RecordingDeadLetterSink : DeadLetterSink {

    private val lock = Any()
    private val recorded = ArrayList<DroppedEvent>()
    private val flushCalls = AtomicInteger()
    private val closeCalls = AtomicInteger()

    /** Immutable snapshot of recorded drops, in arrival order. */
    public fun dropped(): List<DroppedEvent> = synchronized(lock) { recorded.toList() }

    /** Number of recorded drops. */
    public fun count(): Int = synchronized(lock) { recorded.size }

    /** Drop reasons in arrival order. */
    public fun reasons(): List<DropReason> = synchronized(lock) { recorded.map { it.reason } }

    /** Number of [flush] calls. */
    public fun flushCallCount(): Int = flushCalls.get()

    /** Number of [close] calls. */
    public fun closeCallCount(): Int = closeCalls.get()

    override suspend fun write(dropped: DroppedEvent) {
        synchronized(lock) { recorded.add(dropped) }
    }

    override suspend fun flush() {
        flushCalls.incrementAndGet()
    }

    override fun close() {
        closeCalls.incrementAndGet()
    }
}
