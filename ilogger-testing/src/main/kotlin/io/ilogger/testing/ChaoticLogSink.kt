package io.ilogger.testing

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Deterministic chaos wrapper: forwards to [delegate] while injecting seeded
 * write failures and latency, so tests can prove the engine isolates faults
 * without flaky randomness.
 *
 * Given the same seed and the same call sequence, the same writes fail and the
 * same writes are delayed. Failures throw [IOException] before the delegate is
 * touched, so a failed call records nothing. [writeAll] makes one decision for
 * the whole batch, preserving the delegate's batching.
 *
 * Typical use: run a producer loop against a logger configured with `RETRY` or
 * `DROP`, then assert `delivered + dropped == attempted` and that neither
 * producers nor shutdown observe an exception.
 *
 * Not a [io.ilogger.core.DirectLogSink]: INLINE diagnostics go through the
 * coroutine path so injected delay is honoured.
 */
public class ChaoticLogSink(
    public val delegate: LogSink,
    seed: Long = 0L,
    private val failureRate: Double = 0.2,
    private val slowRate: Double = 0.1,
    private val maxDelayMillis: Long = 5L,
    private val closeFailureRate: Double = 0.0,
) : LogSink {

    init {
        require(failureRate in 0.0..1.0) { "failureRate must be in [0, 1], got $failureRate" }
        require(slowRate in 0.0..1.0) { "slowRate must be in [0, 1], got $slowRate" }
        require(maxDelayMillis >= 0) { "maxDelayMillis must be >= 0, got $maxDelayMillis" }
        require(closeFailureRate in 0.0..1.0) { "closeFailureRate must be in [0, 1], got $closeFailureRate" }
    }

    private val seed: Long = seed
    private val random = Random(seed)
    private val randomLock = Any()
    private val calls = AtomicInteger()
    private val attemptedEvents = AtomicInteger()
    private val failedCalls = AtomicInteger()
    private val delayedCalls = AtomicInteger()

    /** Number of write/writeAll calls, including failing ones. */
    public fun callCount(): Int = calls.get()

    /** Number of events attempted across all calls. */
    public fun attemptedEventCount(): Int = attemptedEvents.get()

    /** Number of calls that threw an injected failure. */
    public fun failedCallCount(): Int = failedCalls.get()

    /** Number of calls that slept on an injected delay. */
    public fun delayedCallCount(): Int = delayedCalls.get()

    override suspend fun write(event: LogEvent) {
        calls.incrementAndGet()
        attemptedEvents.incrementAndGet()
        val decision = decide()
        if (decision.fail) {
            failedCalls.incrementAndGet()
            throw IOException("chaos[$seed]: simulated write failure")
        }
        if (decision.delayMillis > 0) {
            delayedCalls.incrementAndGet()
            delay(decision.delayMillis)
        }
        delegate.write(event)
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return delegate.writeAll(events)
        calls.incrementAndGet()
        attemptedEvents.addAndGet(events.size)
        val decision = decide()
        if (decision.fail) {
            failedCalls.incrementAndGet()
            throw IOException("chaos[$seed]: simulated batch failure")
        }
        if (decision.delayMillis > 0) {
            delayedCalls.incrementAndGet()
            delay(decision.delayMillis)
        }
        delegate.writeAll(events)
    }

    override suspend fun flush(): Unit = delegate.flush()

    override fun close() {
        val fail = synchronized(randomLock) { random.nextDouble() < closeFailureRate }
        delegate.close()
        if (fail) throw IOException("chaos[$seed]: simulated close failure")
    }

    private fun decide(): Decision = synchronized(randomLock) {
        val fail = random.nextDouble() < failureRate
        val delayMillis = if (!fail && random.nextDouble() < slowRate) {
            if (maxDelayMillis <= 0) 0 else random.nextLong(1, maxDelayMillis + 1)
        } else {
            0
        }
        Decision(fail, delayMillis)
    }

    private class Decision(val fail: Boolean, val delayMillis: Long)
}
