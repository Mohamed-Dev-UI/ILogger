package io.ilogger.jul

import io.ilogger.annotations.DispatchMode
import io.ilogger.core.ILogger
import io.ilogger.core.LogEventDraft
import io.ilogger.annotations.LogLevel
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * Forwards [java.util.logging] records into an ILogger as structured events.
 *
 * Install it on a JUL logger (or the root logger) to capture third-party
 * libraries that log through JUL:
 *
 * ```kotlin
 * val handler = JulLogHandler(logger)
 * Logger.getLogger("com.example.legacy").addHandler(handler)
 * Logger.getLogger("com.example.legacy").level = Level.ALL
 * ```
 *
 * Each record becomes one event: `action = "<actionPrefix>.<jul logger name>"`,
 * `source = <jul logger name>`, the message and parameters land in metadata,
 * and `LogRecord.thrown` becomes the event failure with its full stack.
 *
 * Recursion is guarded twice: records from logger names under
 * [JulLogSink.BRIDGE_LOGGER_PREFIX] are ignored, and a thread-local guard
 * suppresses records emitted while a bridge sink is publishing.
 *
 * @property logger destination logger
 * @property actionPrefix prefix for generated action codes
 * @property ignoredLoggerPrefixes logger names excluded from forwarding
 * @property dispatch dispatch mode for forwarded events (ASYNC by default)
 * @property timeoutMillis bound for admission into the ILogger pipeline
 * @property onError receives forwarding failures; JUL is never broken by them
 */
public class JulLogHandler(
    private val logger: ILogger,
    private val actionPrefix: String = "jul",
    private val ignoredLoggerPrefixes: Set<String> = setOf(JulLogSink.BRIDGE_LOGGER_PREFIX),
    private val dispatch: DispatchMode = DispatchMode.ASYNC,
    private val timeoutMillis: Long = 5_000,
    private val onError: ((Throwable) -> Unit)? = null,
) : Handler() {

    init {
        require(actionPrefix.isNotBlank()) { "actionPrefix must not be blank" }
        require(timeoutMillis >= 0) { "timeoutMillis must be >= 0, got $timeoutMillis" }
    }

    override fun publish(record: LogRecord) {
        if (!isLoggable(record)) return
        val loggerName = record.loggerName ?: return
        if (loggerName.isBridgeName()) return
        if (JulBridgeGuard.isActive) return
        if (!logger.hasSinks()) return

        val draft = LogEventDraft(
            action = if (loggerName.isBlank()) actionPrefix else "$actionPrefix.$loggerName",
            level = levelFor(record.level),
            failure = record.thrown,
            metadata = metadataFor(record),
            source = loggerName,
            dispatch = dispatch,
        )
        JulBridgeGuard.run {
            try {
                logger.recordBlocking(draft, timeoutMillis)
            } catch (error: Throwable) {
                onError?.invoke(error)
            }
        }
    }

    override fun flush() {
        try {
            logger.flush(timeoutMillis)
        } catch (_: Throwable) {
            // JUL shutdown must not be blocked by a failing sink.
        }
    }

    override fun close() {
        // The handler does not own the logger; closing it only stops
        // forwarding, which the caller controls by removing the handler.
    }

    private fun String.isBridgeName(): Boolean =
        ignoredLoggerPrefixes.any { prefix -> this == prefix || startsWith("$prefix.") }

    private fun metadataFor(record: LogRecord): Map<String, Any?> {
        val metadata = LinkedHashMap<String, Any?>()
        metadata["logger"] = record.loggerName ?: ""
        record.message?.let { metadata["message"] = it }
        record.parameters?.forEachIndexed { index, parameter ->
            metadata["param.$index"] = parameter?.toString() ?: "null"
        }
        return metadata
    }

    private fun levelFor(level: Level): LogLevel = when {
        level.intValue() >= Level.SEVERE.intValue() -> LogLevel.ERROR
        level.intValue() >= Level.WARNING.intValue() -> LogLevel.WARN
        level.intValue() >= Level.INFO.intValue() -> LogLevel.INFO
        level.intValue() >= Level.FINE.intValue() -> LogLevel.DEBUG
        else -> LogLevel.TRACE
    }
}
