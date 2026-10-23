package io.ilogger.jul

import io.ilogger.core.LogEvent
import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogSink
import io.ilogger.core.sink.DefaultTextEventCodec
import io.ilogger.core.sink.EventCodec
import java.time.Instant
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Forwards ILogger events into [java.util.logging].
 *
 * Use it when an existing JUL pipeline (handlers, formatters, `logging.properties`)
 * should also receive audit events:
 *
 * ```kotlin
 * ILogger.create {
 *     sink(JulLogSink())                          // JUL: io.ilogger.bridge.events
 *     sink(ConsoleLogSink())                      // keep the developer view too
 * }
 * ```
 *
 * The logger name starts with [BRIDGE_LOGGER_PREFIX] so [JulLogHandler] ignores
 * it by default; that prefix is what breaks a JUL → ILogger → JUL loop when
 * both bridges are installed.
 *
 * @property loggerName JUL logger to publish to
 * @property codec renders the event line; the fixed text codec by default
 * @property auditLevel JUL level for [LogLevel.AUDIT] (INFO by default)
 */
public class JulLogSink(
    public val loggerName: String = BRIDGE_LOGGER_PREFIX + ".events",
    private val codec: EventCodec = DefaultTextEventCodec,
    private val auditLevel: Level = Level.INFO,
) : LogSink {

    init {
        require(loggerName.isNotBlank()) { "loggerName must not be blank" }
    }

    private val logger: Logger = Logger.getLogger(loggerName)

    override suspend fun write(event: LogEvent): Unit = publish(event)

    override suspend fun writeAll(events: List<LogEvent>): Unit = events.forEach(::publish)

    private fun publish(event: LogEvent) {
        if (JulBridgeGuard.isActive) return
        JulBridgeGuard.run {
            val record = LogRecord(levelFor(event.level), codec.encode(event))
            record.loggerName = loggerName
            record.instant = Instant.ofEpochMilli(event.timestampMillis)
            event.source?.let { record.sourceClassName = it }
            logger.log(record)
        }
    }

    private fun levelFor(level: LogLevel): Level = when (level) {
        LogLevel.TRACE -> Level.FINEST
        LogLevel.DEBUG -> Level.FINE
        LogLevel.INFO -> Level.INFO
        LogLevel.WARN -> Level.WARNING
        LogLevel.ERROR -> Level.SEVERE
        LogLevel.AUDIT -> auditLevel
    }

    public companion object {
        /** Logger-name prefix reserved for the outbound bridge. */
        public const val BRIDGE_LOGGER_PREFIX: String = "io.ilogger.bridge"
    }
}

/**
 * Thread-local recursion guard shared by [JulLogSink] and [JulLogHandler].
 *
 * The logger-name prefix stops the asynchronous loop (the sink's record is
 * ignored by the handler on any thread); the thread-local guard catches
 * synchronous dispatch on the same thread before the record is even built.
 */
internal object JulBridgeGuard {
    private val active: ThreadLocal<Boolean> = ThreadLocal()

    val isActive: Boolean
        get() = active.get() == true

    fun <T> run(block: () -> T): T {
        active.set(true)
        try {
            return block()
        } finally {
            active.remove()
        }
    }
}
