package io.ilogger.core.sink

import io.ilogger.core.LogEvent
import io.ilogger.core.Outcome

/**
 * Converts a [LogEvent] to its textual representation for text-based sinks
 * (console, files, HTTP). Implementations must be side-effect free, fast and
 * safe for concurrent calls.
 */
public fun interface EventCodec {
    public fun encode(event: LogEvent): String
}

/**
 * Human-oriented single-line codec used by default by [ConsoleLogSink].
 * This is deliberately not a general serializer: structured consumption of
 * events should use [JsonEventCodec][io.ilogger.serialization.JsonEventCodec]
 * (optional `ilogger-serialization` module) or application-specific codecs.
 */
public object DefaultTextEventCodec : EventCodec {

    override fun encode(event: LogEvent): String = buildString(96) {
        append(event.timestampMillis)
        append(" [")
        append(event.level.name)
        append("] ")
        append(event.schemaId)
        event.outcome.takeIf { it != Outcome.SUCCESS }?.let { append(" (").append(it.name.lowercase()).append(')') }
        event.actor?.let { append(" actor=").append(it) }
        event.entity?.let { a -> append(' ').append(a); event.entityId?.let { append('(').append(it).append(')') } }
        event.source?.let { append(" via ").append(it) }
        event.correlationId?.let { append(" corr=").append(it) }
        event.causationId?.let { append(" cause=").append(it) }
        if (event.metadata.isNotEmpty()) append(' ').append(event.metadata)
        if (event.changes.isNotEmpty()) append(" changes=").append(event.changes)
        event.failure?.let { f ->
            append(" failure{").append(f.errorType)
            f.message?.let { append(": ").append(it) }
            append('}')
        }
    }
}
