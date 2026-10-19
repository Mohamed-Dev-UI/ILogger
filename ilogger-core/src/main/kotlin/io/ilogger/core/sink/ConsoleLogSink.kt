package io.ilogger.core.sink

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink

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
) : LogSink {

    public constructor(codec: EventCodec = DefaultTextEventCodec) : this(codec, ::println)

    private val lock = Any()

    override suspend fun write(event: LogEvent) {
        val line = codec.encode(event)
        synchronized(lock) { writeLine(line) }
    }
}
