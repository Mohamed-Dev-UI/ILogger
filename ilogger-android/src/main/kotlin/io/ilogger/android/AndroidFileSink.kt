package io.ilogger.android

import android.content.Context
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.sink.EventCodec
import io.ilogger.core.sink.TextFileSink
import io.ilogger.serialization.JsonEventCodec
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * A rotation-capable JSONL file sink that never performs IO on the caller's
 * thread.
 *
 * [TextFileSink] is a [io.ilogger.core.DirectLogSink], so `INLINE` dispatch
 * would write from the producer thread — on Android that can be the main
 * thread and trip StrictMode, or cause an ANR on a slow device. This wrapper
 * deliberately implements only [LogSink]: every write, including batches,
 * hops to a dedicated single-threaded IO dispatcher, and inline dispatch
 * falls back to the regular pipeline.
 *
 * Files default to `<filesDir>/ilogger/ilogger.jsonl`; the JSONL format is
 * readable by `JsonEventCodec` consumers and by standard line tooling.
 */
public class AndroidFileSink internal constructor(
    private val delegate: TextFileSink,
    private val ioDispatcher: CoroutineDispatcher,
    /** Absolute path of the active file. */
    public val path: String,
) : LogSink {

    override suspend fun write(event: LogEvent) {
        delegate.write(event)
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        withContext(ioDispatcher) {
            events.forEach(delegate::writeDirect)
        }
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }

    public companion object {
        /**
         * Creates a sink under the application's private files directory.
         *
         * @param directory subdirectory of `filesDir`.
         * @param rotation optional size/time retention, forwarded to
         *   [TextFileSink.Rotation].
         * @param fsyncEveryWrite force each event to storage; for audit data
         *   that must survive power loss.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        public fun create(
            context: Context,
            name: String = "ilogger.jsonl",
            directory: String = "ilogger",
            codec: EventCodec = JsonEventCodec,
            rotation: TextFileSink.Rotation? = null,
            fsyncEveryWrite: Boolean = false,
        ): AndroidFileSink = create(
            file = File(File(context.applicationContext.filesDir, directory), name),
            codec = codec,
            rotation = rotation,
            fsyncEveryWrite = fsyncEveryWrite,
        )

        /** Creates a sink for an explicit file (tests, custom storage locations). */
        @OptIn(ExperimentalCoroutinesApi::class)
        public fun create(
            file: File,
            codec: EventCodec = JsonEventCodec,
            rotation: TextFileSink.Rotation? = null,
            fsyncEveryWrite: Boolean = false,
        ): AndroidFileSink {
            val dispatcher = Dispatchers.IO.limitedParallelism(1, name = "ilogger-android-file")
            val delegate = TextFileSink(
                file = file.toPath(),
                codec = codec,
                append = true,
                flushEveryWrite = true,
                rotation = rotation,
                fsyncEveryWrite = fsyncEveryWrite,
                ioDispatcher = dispatcher,
            )
            return AndroidFileSink(delegate, dispatcher, file.absolutePath)
        }
    }
}
