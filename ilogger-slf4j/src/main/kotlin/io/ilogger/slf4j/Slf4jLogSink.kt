package io.ilogger.slf4j

import io.ilogger.core.LogEvent
import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogSink
import io.ilogger.core.sink.DefaultTextEventCodec
import io.ilogger.core.sink.EventCodec
import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Forwards ILogger events into SLF4J (and therefore into Logback, Log4j 2 or
 * any other SLF4J binding).
 *
 * ```kotlin
 * ILogger.create { sink(Slf4jLogSink()) }
 * ```
 *
 * Events are logged at the mapped level; event id, action, entity, actor,
 * correlation and causation are attached as SLF4J 2 key/values
 * (`ilogger.*`), so structured layouts can render them without parsing the
 * message. The message itself is the fixed text codec's line.
 *
 * The logger name starts with [BRIDGE_LOGGER_PREFIX] so [ILoggerAppender]
 * ignores it by default; that is what stops an SLF4J → ILogger → SLF4J loop
 * when both bridges are installed.
 *
 * @property loggerName SLF4J logger to publish to
 * @property codec renders the event line
 * @property auditLevel SLF4J level for [LogLevel.AUDIT] (INFO by default)
 * @property factory logger factory (defaults to the SLF4J binding)
 */
public class Slf4jLogSink(
    public val loggerName: String = BRIDGE_LOGGER_PREFIX + ".events",
    private val codec: EventCodec = DefaultTextEventCodec,
    private val auditLevel: Level = Level.INFO,
    factory: ILoggerFactory = LoggerFactory.getILoggerFactory(),
) : LogSink {

    init {
        require(loggerName.isNotBlank()) { "loggerName must not be blank" }
    }

    private val logger: org.slf4j.Logger = factory.getLogger(loggerName)

    override suspend fun write(event: LogEvent): Unit = publish(event)

    override suspend fun writeAll(events: List<LogEvent>): Unit = events.forEach(::publish)

    private fun publish(event: LogEvent) {
        if (Slf4jBridgeGuard.isActive) return
        Slf4jBridgeGuard.run {
            val builder = logger.atLevel(levelFor(event.level))
                .addKeyValue("ilogger.eventId", event.id)
                .addKeyValue("ilogger.action", event.schemaId)
            event.entity?.let { builder.addKeyValue("ilogger.entity", it) }
            event.entityId?.let { builder.addKeyValue("ilogger.entityId", it) }
            event.actor?.let { builder.addKeyValue("ilogger.actor", it.toString()) }
            event.correlationId?.let { builder.addKeyValue("ilogger.correlationId", it) }
            event.causationId?.let { builder.addKeyValue("ilogger.causationId", it) }
            builder.log(codec.encode(event))
        }
    }

    private fun levelFor(level: LogLevel): Level = when (level) {
        LogLevel.TRACE -> Level.TRACE
        LogLevel.DEBUG -> Level.DEBUG
        LogLevel.INFO -> Level.INFO
        LogLevel.WARN -> Level.WARN
        LogLevel.ERROR -> Level.ERROR
        LogLevel.AUDIT -> auditLevel
    }

    public companion object {
        /** Logger-name prefix reserved for the outbound bridge. */
        public const val BRIDGE_LOGGER_PREFIX: String = "io.ilogger.bridge"
    }
}

/**
 * Thread-local recursion guard shared by [Slf4jLogSink] and [ILoggerAppender].
 *
 * The logger-name prefix stops asynchronous loops; this guard catches a
 * synchronous SLF4J appender called while the sink is publishing on the same
 * thread.
 */
internal object Slf4jBridgeGuard {
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
