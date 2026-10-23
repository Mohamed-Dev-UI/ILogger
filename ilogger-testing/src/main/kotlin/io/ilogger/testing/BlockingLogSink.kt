package io.ilogger.testing

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A sink that holds every write until [release] is called.
 *
 * Use it to test backpressure, queue overflow and shutdown timing: the first
 * write signals [awaitFirstWrite], subsequent writes wait on the same gate, and
 * nothing is recorded before the gate opens. [close] also releases the gate so
 * engine shutdown cannot hang on a blocked writer.
 *
 * The gate is one-shot: after [release] every current and future write passes.
 * Writes that were cancelled while waiting never record their event.
 */
public class BlockingLogSink(
    public val name: String = "blocking",
) : LogSink {

    private val lock = Any()
    private val recorded = ArrayList<LogEvent>()
    private val firstWrite = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    /** Accepted events, in completion order. */
    public fun events(): List<LogEvent> = synchronized(lock) { recorded.toList() }

    /** Number of accepted events. */
    public fun count(): Int = synchronized(lock) { recorded.size }

    /** Whether a write reached the gate already. */
    public fun firstWriteReached(): Boolean = firstWrite.isCompleted

    /** Waits up to [timeoutMillis] for the first write; `false` on timeout. */
    public suspend fun awaitFirstWrite(timeoutMillis: Long = 5_000L): Boolean =
        withTimeoutOrNull(timeoutMillis) { firstWrite.await() } != null

    /** Opens the gate for every waiting and future write. */
    public fun release() {
        gate.complete(Unit)
    }

    override suspend fun write(event: LogEvent) {
        firstWrite.complete(Unit)
        gate.await()
        synchronized(lock) { recorded.add(event) }
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        firstWrite.complete(Unit)
        gate.await()
        synchronized(lock) { recorded.addAll(events) }
    }

    override fun close() {
        gate.complete(Unit)
    }
}
