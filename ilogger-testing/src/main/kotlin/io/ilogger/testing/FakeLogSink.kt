package io.ilogger.testing

import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogEvent
import io.ilogger.core.sink.DefaultTextEventCodec
import io.ilogger.core.sink.EventCodec
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Scriptable fake destination for tests: a file, Room table or HTTP collector
 * stand-in that records what a real sink would persist and lets tests inject
 * failures, delays and reset state.
 *
 * What is recorded:
 * - [events] — every accepted event, arrival order (the logical rows);
 * - [batches] — one list per explicit [writeAll] call (batch envelope);
 * - [payloads] — [codec]-encoded text per accepted event (file lines / HTTP
 *   bodies);
 * - call counters for writes, flushes and closes.
 *
 * Failure injection is per call, not per event: a failing [writeAll] records
 * none of its events, matching a destination that rejects the request. Configure
 * [failNextWrites] for a transient outage, [failEveryNthWrite] for a
 * deterministic flaky endpoint, and [failFlushWith]/[failCloseWith] for shutdown
 * faults. [delayWrites] uses `kotlinx.coroutines.delay`, so it cooperates with
 * virtual-time test schedulers.
 *
 * The fake is thread-safe and implements [DirectLogSink], so it can stand in
 * for INLINE diagnostics and be written from several threads.
 */
public class FakeLogSink(
    private val codec: EventCodec = DefaultTextEventCodec,
    public val name: String = "fake",
) : DirectLogSink {

    private val lock = Any()
    private val recordedEvents = ArrayList<LogEvent>()
    private val recordedBatches = ArrayList<List<LogEvent>>()
    private val recordedPayloads = ArrayList<String>()

    private val writeCalls = AtomicInteger()
    private val writeAllCalls = AtomicInteger()
    private val writtenEvents = AtomicInteger()
    private val attemptedEvents = AtomicInteger()
    private val failedWrites = AtomicInteger()
    private val flushCalls = AtomicInteger()
    private val closeCalls = AtomicInteger()

    private val failuresRemaining = AtomicInteger()
    private val failureEveryNth = AtomicInteger()

    @Volatile
    private var failureFactory: (LogEvent) -> Throwable = { IllegalStateException("$name write failed") }

    @Volatile
    private var flushFailure: Throwable? = null

    @Volatile
    private var closeFailure: Throwable? = null

    @Volatile
    private var writeDelayMillis: Long = 0

    /** Accepted events, in arrival order. */
    public fun events(): List<LogEvent> = synchronized(lock) { recordedEvents.toList() }

    /** One entry per [writeAll] call, including empty batches. */
    public fun batches(): List<List<LogEvent>> = synchronized(lock) { recordedBatches.map { it.toList() } }

    /** [codec]-encoded text of every accepted event, in arrival order. */
    public fun payloads(): List<String> = synchronized(lock) { recordedPayloads.toList() }

    /** Most recently accepted event, or null. */
    public fun last(): LogEvent? = synchronized(lock) { recordedEvents.lastOrNull() }

    /** Number of accepted events. */
    public fun count(): Int = synchronized(lock) { recordedEvents.size }

    /** Number of [write] plus [writeAll] calls, including failing ones. */
    public fun writeCallCount(): Int = writeCalls.get() + writeAllCalls.get()

    /** Number of [writeAll] calls. */
    public fun writeAllCallCount(): Int = writeAllCalls.get()

    /** Number of events successfully accepted (not merely attempted). */
    public fun writtenEventCount(): Int = writtenEvents.get()

    /** Number of events attempted, including events of failing calls. */
    public fun attemptedEventCount(): Int = attemptedEvents.get()

    /** Number of calls that threw a configured failure. */
    public fun failedWriteCount(): Int = failedWrites.get()

    /** Number of [flush] calls, including failing ones. */
    public fun flushCallCount(): Int = flushCalls.get()

    /** Number of [close] calls, including failing ones. */
    public fun closeCallCount(): Int = closeCalls.get()

    /** Fails the next [count] write calls with [error] (default: `IllegalStateException`). */
    public fun failNextWrites(
        count: Int,
        error: (LogEvent) -> Throwable = { IllegalStateException("$name write failed") },
    ): FakeLogSink {
        require(count > 0) { "count must be > 0, got $count" }
        failureFactory = error
        failuresRemaining.set(count)
        return this
    }

    /** Fails every [n]th write call (1-based); `0` disables the cadence. */
    public fun failEveryNthWrite(
        n: Int,
        error: (LogEvent) -> Throwable = { IllegalStateException("$name write failed") },
    ): FakeLogSink {
        require(n >= 0) { "n must be >= 0, got $n" }
        if (n > 0) failureFactory = error
        failureEveryNth.set(n)
        return this
    }

    /** Makes [flush] throw [error] until called again with `null`. */
    public fun failFlushWith(error: Throwable?): FakeLogSink {
        flushFailure = error
        return this
    }

    /** Makes [close] throw [error] until called again with `null`. */
    public fun failCloseWith(error: Throwable?): FakeLogSink {
        closeFailure = error
        return this
    }

    /** Suspends each write by [millis] before recording or failing. */
    public fun delayWrites(millis: Long): FakeLogSink {
        require(millis >= 0) { "millis must be >= 0, got $millis" }
        writeDelayMillis = millis
        return this
    }

    /** Clears recordings, counters and all failure/delay configuration. */
    public fun reset(): FakeLogSink {
        synchronized(lock) {
            recordedEvents.clear()
            recordedBatches.clear()
            recordedPayloads.clear()
        }
        writeCalls.set(0)
        writeAllCalls.set(0)
        writtenEvents.set(0)
        attemptedEvents.set(0)
        failedWrites.set(0)
        flushCalls.set(0)
        closeCalls.set(0)
        failuresRemaining.set(0)
        failureEveryNth.set(0)
        failureFactory = { IllegalStateException("$name write failed") }
        flushFailure = null
        closeFailure = null
        writeDelayMillis = 0
        return this
    }

    override suspend fun write(event: LogEvent) {
        writeCalls.incrementAndGet()
        attemptedEvents.incrementAndGet()
        awaitDelay()
        failIfConfigured(event)?.let { throw it }
        record(listOf(event), batch = null)
    }

    override fun writeDirect(event: LogEvent) {
        writeCalls.incrementAndGet()
        attemptedEvents.incrementAndGet()
        failIfConfigured(event)?.let { throw it }
        record(listOf(event), batch = null)
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        writeAllCalls.incrementAndGet()
        if (events.isEmpty()) {
            synchronized(lock) { recordedBatches.add(emptyList()) }
            return
        }
        attemptedEvents.addAndGet(events.size)
        awaitDelay()
        failIfConfigured(events.first())?.let { throw it }
        record(events, batch = events)
    }

    override suspend fun flush() {
        flushCalls.incrementAndGet()
        flushFailure?.let { throw it }
    }

    override fun close() {
        closeCalls.incrementAndGet()
        closeFailure?.let { throw it }
    }

    private suspend fun awaitDelay() {
        val millis = writeDelayMillis
        if (millis > 0) delay(millis)
    }

    private fun failIfConfigured(event: LogEvent): Throwable? {
        val every = failureEveryNth.get()
        val byCadence = every > 0 && writeCallCount() % every == 0
        val byRemaining = failuresRemaining.getAndUpdate { remaining ->
            if (remaining > 0) remaining - 1 else 0
        } > 0
        if (!byCadence && !byRemaining) return null

        failedWrites.incrementAndGet()
        return failureFactory(event)
    }

    private fun record(events: List<LogEvent>, batch: List<LogEvent>?) {
        val payloads = events.map(codec::encode)
        synchronized(lock) {
            recordedEvents.addAll(events)
            if (batch != null) recordedBatches.add(batch.toList())
            recordedPayloads.addAll(payloads)
        }
        writtenEvents.addAndGet(events.size)
    }
}
