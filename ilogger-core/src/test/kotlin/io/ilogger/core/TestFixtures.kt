package io.ilogger.core

import kotlinx.coroutines.delay
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class RecordingSink : DirectLogSink {
    val events = Collections.synchronizedList(ArrayList<LogEvent>())
    val batches = Collections.synchronizedList(ArrayList<List<LogEvent>>())

    override suspend fun write(event: LogEvent): Unit = writeDirect(event)

    override fun writeDirect(event: LogEvent) {
        events.add(event)
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        batches.add(events.toList())
        this.events.addAll(events)
    }

    fun count(): Int = synchronized(events) { events.size }

    fun actions(): List<String> = synchronized(events) { events.map { it.action } }
}

internal class BlockingSink : LogSink {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val events = Collections.synchronizedList(ArrayList<LogEvent>())

    override suspend fun write(event: LogEvent) {
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
        events.add(event)
    }

    fun count(): Int = synchronized(events) { events.size }
}

internal class BatchSink(private val failOnBatch: Boolean = false) : LogSink {
    val batches = Collections.synchronizedList(ArrayList<List<LogEvent>>())
    val singleWrites = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun write(event: LogEvent) {
        singleWrites.incrementAndGet()
        if (failOnBatch) throw IllegalStateException("single write failed")
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        batches.add(events.toList())
        if (failOnBatch) throw IllegalStateException("batch write failed")
    }

    fun writtenCount(): Int = synchronized(batches) { batches.sumOf { it.size } }
}

internal class FailingSink(
    private val error: Throwable = IllegalStateException("sink down"),
) : LogSink {
    val attempts = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun write(event: LogEvent) {
        attempts.incrementAndGet()
        throw error
    }
}

internal class CloseFailingSink : LogSink {
    override suspend fun write(event: LogEvent): Unit = Unit

    override fun close() {
        throw IllegalStateException("cannot close")
    }
}

internal class ReentrantSink : LogSink {
    @Volatile
    var logger: ILogger? = null

    val writes = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun write(event: LogEvent) {
        writes.incrementAndGet()
        logger?.recordNow(
            action = "reentrant.event",
            dispatch = io.ilogger.annotations.DispatchMode.INLINE,
        )
    }
}

internal suspend fun awaitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) return false
        delay(5)
    }
    return true
}
