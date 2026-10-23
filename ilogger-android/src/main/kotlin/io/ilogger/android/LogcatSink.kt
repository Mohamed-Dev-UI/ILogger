package io.ilogger.android

import android.util.Log
import io.ilogger.annotations.LogLevel
import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogEvent
import io.ilogger.core.sink.DefaultTextEventCodec
import io.ilogger.core.sink.EventCodec

/**
 * Writes events to Android Logcat through [android.util.Log.println].
 *
 * The sink is a [DirectLogSink], so events routed with
 * `DispatchMode.INLINE` are written on the caller thread without coroutine
 * machinery. Logcat itself is cheap and non-blocking; it is safe to use on the
 * main thread (unlike file or network sinks).
 *
 * @param tag default Logcat tag; override per event with [tagFor].
 * @param codec line format; the human-readable default keeps Logcat readable,
 *   while `JsonEventCodec` can be supplied when logcat is scraped by tooling.
 * @param tagFor tag selection; defaults to the fixed [tag].
 * @param priorityFor level-to-priority mapping; see [priorityOf].
 * @param maxChunkLength Logcat truncates entries above ~4 KB, so longer lines
 *   are split into consecutive entries instead of being silently lost.
 * @param writeLine output seam for tests and for hosts that wrap Logcat.
 */
public class LogcatSink(
    private val tag: String = DEFAULT_TAG,
    private val codec: EventCodec = DefaultTextEventCodec,
    private val tagFor: (LogEvent) -> String = { tag },
    private val priorityFor: (LogLevel) -> Int = LogcatSink::priorityOf,
    private val maxChunkLength: Int = MAX_CHUNK_LENGTH,
    private val writeLine: (priority: Int, tag: String, line: String) -> Unit =
        { priority, eventTag, line -> Log.println(priority, eventTag, line) },
) : DirectLogSink {

    init {
        require(maxChunkLength > 0) { "maxChunkLength must be positive" }
    }

    override suspend fun write(event: LogEvent) {
        writeDirect(event)
    }

    override fun writeDirect(event: LogEvent) {
        val line = codec.encode(event)
        val priority = priorityFor(event.level)
        val eventTag = tagFor(event)
        if (line.length <= maxChunkLength) {
            writeLine(priority, eventTag, line)
        } else {
            line.chunked(maxChunkLength).forEach { chunk -> writeLine(priority, eventTag, chunk) }
        }
    }

    public companion object {
        public const val DEFAULT_TAG: String = "ILogger"

        /** Values mirror `android.util.Log` priorities without loading Log in tests. */
        public const val PRIORITY_VERBOSE: Int = 2
        public const val PRIORITY_DEBUG: Int = 3
        public const val PRIORITY_INFO: Int = 4
        public const val PRIORITY_WARN: Int = 5
        public const val PRIORITY_ERROR: Int = 6

        /** Logcat's per-entry limit is 4 KB; leave room for the tag and metadata. */
        public const val MAX_CHUNK_LENGTH: Int = 3_800

        /**
         * Level mapping: audit events use INFO — they are records, not faults —
         * and warnings/errors keep their native priorities.
         */
        public fun priorityOf(level: LogLevel): Int = when (level) {
            LogLevel.TRACE -> PRIORITY_VERBOSE
            LogLevel.DEBUG -> PRIORITY_DEBUG
            LogLevel.INFO -> PRIORITY_INFO
            LogLevel.WARN -> PRIORITY_WARN
            LogLevel.ERROR -> PRIORITY_ERROR
            LogLevel.AUDIT -> PRIORITY_INFO
        }
    }
}
