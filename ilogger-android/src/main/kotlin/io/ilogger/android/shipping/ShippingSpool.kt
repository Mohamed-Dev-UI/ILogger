package io.ilogger.android.shipping

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.sink.EventCodec
import io.ilogger.serialization.JsonEventCodec
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Transport seam for [LogShippingWorker].
 *
 * Implementations send one batch of JSONL lines and return `true` only when the
 * receiver durably accepted them; `false` (or a thrown exception) leaves the
 * spool untouched so WorkManager retries later.
 */
public fun interface ShippingTransport {
    public suspend fun send(batch: List<String>): Boolean
}

/**
 * Durable JSONL queue between the application and [ShippingTransport].
 *
 * Append-only until acknowledged; acknowledgements rewrite the remaining
 * lines atomically enough for a single worker (WorkManager's unique work
 * guarantees one worker per name). All operations hop to [ioDispatcher], so
 * callers never touch disk on the main thread.
 *
 * The spool is intentionally line-based: a batch is a prefix of the file, and
 * acknowledging `N` lines drops exactly those lines. A second producer
 * appending during a send is safe because new lines are only ever appended.
 */
public class ShippingSpool(
    /** Absolute path of the spool file. */
    public val file: File,
    private val ioDispatcher: CoroutineDispatcher = defaultDispatcher(),
    private val codec: EventCodec = JsonEventCodec,
) {

    private val mutex = Mutex()

    /** Appends one encoded line. */
    public suspend fun append(line: String) {
        appendAll(listOf(line))
    }

    /** Appends a batch with one disk round-trip. */
    public suspend fun appendAll(lines: List<String>) {
        if (lines.isEmpty()) return
        withContext(ioDispatcher) {
            mutex.withLock {
                file.parentFile?.mkdirs()
                file.appendText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
            }
        }
    }

    /** Encodes and appends an event; used by [ShippingSpoolSink]. */
    public suspend fun append(event: LogEvent) {
        append(codec.encode(event))
    }

    /** First [limit] pending lines, oldest first. */
    public suspend fun pending(limit: Int = Int.MAX_VALUE): List<String> {
        if (limit <= 0) return emptyList()
        return withContext(ioDispatcher) {
            mutex.withLock {
                if (!file.exists()) return@withLock emptyList<String>()
                file.readLines(Charsets.UTF_8).take(limit)
            }
        }
    }

    /** Drops the first [count] acknowledged lines. */
    public suspend fun acknowledge(count: Int) {
        if (count <= 0) return
        withContext(ioDispatcher) {
            mutex.withLock {
                if (!file.exists()) return@withLock
                val remaining = file.readLines(Charsets.UTF_8).drop(count)
                if (remaining.isEmpty()) {
                    file.delete()
                } else {
                    file.writeText(remaining.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
                }
            }
        }
    }

    /** Number of pending lines (reads the file; not a hot-path counter). */
    public suspend fun size(): Int = withContext(ioDispatcher) {
        mutex.withLock {
            if (!file.exists()) 0 else file.readLines(Charsets.UTF_8).size
        }
    }

    private companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun defaultDispatcher(): CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(1, name = "ilogger-shipping-spool")
    }
}

/**
 * A sink that writes events into a [ShippingSpool] for later delivery by
 * [LogShippingWorker]. It implements only [LogSink], so inline dispatch never
 * performs disk IO on the producer thread.
 */
public class ShippingSpoolSink(
    private val spool: ShippingSpool,
    private val codec: EventCodec = JsonEventCodec,
) : LogSink {

    override suspend fun write(event: LogEvent) {
        spool.append(codec.encode(event))
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        spool.appendAll(events.map(codec::encode))
    }
}
