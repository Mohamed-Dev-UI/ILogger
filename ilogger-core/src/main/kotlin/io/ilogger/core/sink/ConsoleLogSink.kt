package io.ilogger.core.sink

import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogEvent

/**
 * Writes encoded event text to the process console via an injected writer.
 *
 * The writer indirection exists so host platforms can route output elsewhere
 * (e.g. Android Logcat bridge in `ilogger-android` style integrations) without
 * re-implementing the sink.
 *
 * Concurrency: writes are synchronized; under heavy load prefer buffered sinks.
 */
public class ConsoleLogSink(
    private val codec: EventCodec = DefaultTextEventCodec,
    private val writeLine: (String) -> Unit,
) : DirectLogSink {

    public constructor(codec: EventCodec = DefaultTextEventCodec) : this(codec, ::println)

    private val lock = Any()

    override suspend fun write(event: LogEvent): Unit = writeDirect(event)

    override fun writeDirect(event: LogEvent) {
        val line = codec.encode(event)
        synchronized(lock) { writeLine(line) }
    }
}
