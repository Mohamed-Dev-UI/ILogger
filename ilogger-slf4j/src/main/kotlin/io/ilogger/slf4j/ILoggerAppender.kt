package io.ilogger.slf4j

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.AppenderBase
import io.ilogger.annotations.DispatchMode
import io.ilogger.core.ILogger
import io.ilogger.core.LogEventDraft
import io.ilogger.annotations.LogLevel

/**
 * Logback appender that forwards framework logs into an ILogger as structured
 * events.
 *
 * ```kotlin
 * val appender = ILoggerAppender(logger)
 * appender.context = loggerContext
 * appender.start()
 * loggerContext.getLogger("ROOT").addAppender(appender)
 * ```
 *
 * Each record becomes one event: `action = "<actionPrefix>.<logger name>"`,
 * `source = <logger name>`, the formatted message and parameters land in
 * metadata, MDC entries become `mdc.*` metadata, SLF4J 2 key/values become
 * `kv.*`, and the original throwable becomes the event failure with its full
 * stack.
 *
 * Recursion is guarded twice: records from logger names under
 * [Slf4jLogSink.BRIDGE_LOGGER_PREFIX] are ignored, and a thread-local guard
 * suppresses records emitted while a bridge sink is publishing.
 *
 * @property logger destination logger
 * @property actionPrefix prefix for generated action codes
 * @property ignoredLoggerPrefixes logger names excluded from forwarding
 * @property includeMdc copy MDC entries into event metadata
 * @property includeKeyValues copy SLF4J 2 key/value pairs into event metadata
 * @property dispatch dispatch mode for forwarded events (ASYNC by default)
 * @property timeoutMillis bound for admission into the ILogger pipeline
 * @property onError receives forwarding failures; logging is never broken by them
 */
public class ILoggerAppender(
    private val logger: ILogger,
    private val actionPrefix: String = "log",
    private val ignoredLoggerPrefixes: Set<String> = setOf(Slf4jLogSink.BRIDGE_LOGGER_PREFIX),
    private val includeMdc: Boolean = true,
    private val includeKeyValues: Boolean = true,
    private val dispatch: DispatchMode = DispatchMode.ASYNC,
    private val timeoutMillis: Long = 5_000,
    private val onError: ((Throwable) -> Unit)? = null,
) : AppenderBase<ILoggingEvent>() {

    init {
        require(actionPrefix.isNotBlank()) { "actionPrefix must not be blank" }
        require(timeoutMillis >= 0) { "timeoutMillis must be >= 0, got $timeoutMillis" }
    }

    override fun append(eventObject: ILoggingEvent) {
        if (!isStarted) return
        val loggerName = eventObject.loggerName ?: return
        if (loggerName.isBridgeName()) return
        if (Slf4jBridgeGuard.isActive) return
        if (!logger.hasSinks()) return

        val draft = LogEventDraft(
            action = if (loggerName.isBlank()) actionPrefix else "$actionPrefix.$loggerName",
            level = levelFor(eventObject.level),
            failure = (eventObject.throwableProxy as? ThrowableProxy)?.throwable,
            metadata = metadataFor(eventObject),
            source = loggerName,
            dispatch = dispatch,
        )
        Slf4jBridgeGuard.run {
            try {
                logger.recordBlocking(draft, timeoutMillis)
            } catch (error: Throwable) {
                onError?.invoke(error)
            }
        }
    }

    private fun String?.isBridgeName(): Boolean {
        if (this == null) return false
        return ignoredLoggerPrefixes.any { prefix -> this == prefix || startsWith("$prefix.") }
    }

    private fun metadataFor(eventObject: ILoggingEvent): Map<String, Any?> {
        val metadata = LinkedHashMap<String, Any?>()
        metadata["logger"] = eventObject.loggerName ?: ""
        metadata["message"] = eventObject.formattedMessage
        if (includeMdc) {
            // Raw LoggerContexts used by tests (and unusual custom bindings) may
            // have no MDC adapter; losing MDC must never drop the record.
            val contextMap = try {
                eventObject.mdcPropertyMap
            } catch (_: Throwable) {
                null
            }
            contextMap?.forEach { (key, value) -> metadata["mdc.$key"] = value }
        }
        if (includeKeyValues) {
            eventObject.keyValuePairs?.forEach { pair -> metadata["kv.${pair.key}"] = pair.value }
        }
        return metadata
    }

    private fun levelFor(level: Level): LogLevel = when {
        level.isGreaterOrEqual(Level.ERROR) -> LogLevel.ERROR
        level.isGreaterOrEqual(Level.WARN) -> LogLevel.WARN
        level.isGreaterOrEqual(Level.INFO) -> LogLevel.INFO
        level.isGreaterOrEqual(Level.DEBUG) -> LogLevel.DEBUG
        else -> LogLevel.TRACE
    }
}
