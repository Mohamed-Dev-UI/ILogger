package example.advanced

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.ErrorMode
import io.ilogger.core.LogContext
import io.ilogger.core.LogContexts
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.ILogger
import io.ilogger.core.OverflowPolicy
import io.ilogger.core.withActor
import io.ilogger.core.withContextAttributes
import io.ilogger.core.withCorrelation
import io.ilogger.core.sink.ConsoleLogSink
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime engine walkthrough — the annotation layer is optional at runtime:
 * every feature here is the core runtime contract.
 *
 * 1. bounded buffers + DROP_OLDEST backpressure (a flood cannot grow memory)
 * 2. sink failures: AUDIT events retry through transient failures; INFO drops
 * 3. per-level sampling (0.0 suppresses deterministically)
 * 4. nested ambient context: actor → correlation → attributes accumulate
 * 5. close() drains deterministically within closeTimeoutMillis
 */

/** Counts what reaches the sink; optional slow write makes backpressure observable. */
class CountingSink(private val writeDelayMillis: Long = 0) : LogSink {

    val received = AtomicInteger(0)

    override suspend fun write(event: LogEvent) {
        if (writeDelayMillis > 0) Thread.sleep(writeDelayMillis)
        val n = received.incrementAndGet()
        println("    sink <- #${n} ${event.schemaId} ${if (event.outcome == io.ilogger.core.Outcome.SUCCESS) "" else "(${event.outcome})"}")
    }
}

/** Fails (transiently) the first N write attempts in total, then always succeeds. */
class FlakySink(private val transientFailures: Int) : LogSink {

    private val failuresLeft = AtomicInteger(transientFailures)
    val delivered = AtomicInteger(0)

    override suspend fun write(event: LogEvent) {
        if (failuresLeft.getAndDecrement() > 0) throw IllegalStateException("transient sink failure")
        val n = delivered.incrementAndGet()
        println("    sink[flaky] <- ${event.schemaId} (#$n delivered)")
    }
}

/** Always fails — shows the drop path for non-retried levels. */
class AlwaysFailingSink : LogSink {
    override suspend fun write(event: LogEvent) = throw IllegalStateException("sink offline")
}

fun main(): Unit = runBlocking {

    // ─── 1. Backpressure: slow consumer + bounded buffer + DROP_OLDEST ─────────────
    println("— flood 30 events into a buffer of 8 (slow sink, DROP_OLDEST):")
    val counted = CountingSink(writeDelayMillis = 20)
    val backpressured = ILogger.create {
        dispatch = DispatchMode.ASYNC
        overflow = OverflowPolicy.DROP_OLDEST
        bufferSize = 8 // deliberately tiny: overflow is guaranteed
        batchSize = 4
        flushIntervalMillis = 20
        sink(counted)
    }
    backpressured.use {
        repeat(30) { i -> it.info("flood tick", "i" to i) }
        it.flush()
    }
    println("    delivered=${counted.received.get()} of 30 (the oldest were dropped; buffer never grew)")

    // ─── 2. Sink failures: AUDIT retried, INFO dropped ──────────────────────────────
    println("— AUDIT events retry through 2 transient sink failures:")
    val flaky = FlakySink(transientFailures = 2)
    val retrying = ILogger.create {
        dispatch = DispatchMode.ASYNC
        onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
        retryMaxAttempts = 3
        retryBaseBackoffMillis = 10
        onDispatchError = { event, _, error -> println("    [dispatch-error] ${event?.schemaId}: $error") }
        sink(flaky)
    }
    retrying.use {
        it.recordNow(action = "order.placed", level = LogLevel.AUDIT, entity = "Order", entityId = "o-1", source = "example")
        it.recordNow(action = "order.placed", level = LogLevel.AUDIT, entity = "Order", entityId = "o-2", source = "example")
        it.flush()
    }
    println("    delivered=${flaky.delivered.get()} of 2 (attempt-by-attempt retry recovered both)")

    println("— INFO events on a dead sink are dropped, never block the producer:")
    val dropping = ILogger.create {
        dispatch = DispatchMode.ASYNC
        sink(AlwaysFailingSink())
        onDispatchError = { event, sink, error ->
            println("    [dispatch-error] ${event?.schemaId} reachedSink=${sink != null}: ${error.message}")
        }
    }
    dropping.use {
        it.info("will be dropped")
        it.flush()
    }
    println("    producer returned immediately; failure routed to onDispatchError")

    // ─── 3. Sampling + levels ──────────────────────────────────────────────────────
    println("— per-level sampling: TRACE rate 0.0 (deterministic), INFO visible:")
    val counted2 = CountingSink()
    val sampling = ILogger.create {
        dispatch = DispatchMode.ASYNC
        sampling(LogLevel.TRACE to 0.0)
        sink(counted2)
    }
    sampling.use {
        repeat(3) { n -> it.trace("invisible", "n" to n) }
        it.debug("visible", "n" to 0)
        it.flush()
    }
    println("    received=${counted2.received.get()} of 4 (expected 1: the debug event)")

    // ─── 4. Nested ambient context ────────────────────────────────────────────────
    println("— nested actor → correlation → attributes:")
    val audit = ILogger.create {
        dispatch = DispatchMode.INLINE
        sink(ConsoleLogSink())
    }
    audit.use {
        withActor(Actor.user("u-7", name = "Ada", attributes = mapOf("team" to "core"))) {
            withCorrelation("flow-42") {
                withContextAttributes("tenant" to "toronto") {
                    // Suspend record() reads the *coroutine* context — the merged
                    // actor/correlation/attributes appear on the event. (recordNow(),
                    // the non-suspend path, sees only the LogContexts.onCurrentThread
                    // thread-local bridge — a deliberate thread-ownership rule.)
                    it.record(
                        action = "effect.name",
                        level = LogLevel.AUDIT,
                        source = "example.advanced",
                    )
                }
            }
        }
    }
    println("— done")
}
