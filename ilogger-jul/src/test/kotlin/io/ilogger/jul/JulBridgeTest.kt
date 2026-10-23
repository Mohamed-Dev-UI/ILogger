package io.ilogger.jul

import io.ilogger.annotations.DispatchMode
import io.ilogger.core.FixedClock
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.annotations.LogLevel
import io.ilogger.testing.SequentialIdGenerator
import io.ilogger.testing.TestLogSink
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingJulHandler : Handler() {
    val records: MutableList<LogRecord> = mutableListOf()

    override fun publish(record: LogRecord) {
        records += record
    }

    override fun flush() = Unit

    override fun close() = Unit
}

private fun event(
    level: LogLevel,
    action: String = "task.created",
    timestamp: Long = 1_700_000_000_000,
): LogEvent = LogEvent(
    id = "e-$action-${level.name}",
    timestampMillis = timestamp,
    level = level,
    action = action,
    entity = "Task",
    entityId = "42",
    metadata = mapOf("task.title" to "Write docs"),
    source = "TaskRepository.insert",
)

class JulLogSinkTest {

    @Test
    fun `maps every level to its java util logging counterpart`() = runBlocking {
        val loggerName = "test.jul.sink.levels"
        val logger = Logger.getLogger(loggerName)
        logger.level = Level.ALL
        val recorder = RecordingJulHandler()
        logger.addHandler(recorder)
        try {
            val sink = JulLogSink(loggerName = loggerName)
            sink.writeAll(
                listOf(
                    event(LogLevel.TRACE),
                    event(LogLevel.DEBUG),
                    event(LogLevel.INFO),
                    event(LogLevel.WARN),
                    event(LogLevel.ERROR),
                    event(LogLevel.AUDIT),
                ),
            )

            assertEquals(
                listOf(Level.FINEST, Level.FINE, Level.INFO, Level.WARNING, Level.SEVERE, Level.INFO),
                recorder.records.map { it.level },
            )
        } finally {
            logger.removeHandler(recorder)
        }
    }

    @Test
    fun `carries the timestamp source and encoded event into the record`() = runBlocking {
        val loggerName = "test.jul.sink.fields"
        val logger = Logger.getLogger(loggerName)
        logger.level = Level.ALL
        val recorder = RecordingJulHandler()
        logger.addHandler(recorder)
        try {
            JulLogSink(loggerName = loggerName).write(event(LogLevel.INFO, timestamp = 1_234_567L))

            val record = recorder.records.single()
            assertEquals(1_234_567L, record.instant.toEpochMilli())
            assertEquals(loggerName, record.loggerName)
            assertEquals("TaskRepository.insert", record.sourceClassName)
            assertTrue(record.message.contains("task.created"))
            assertTrue(record.message.contains("Write docs"))
        } finally {
            logger.removeHandler(recorder)
        }
    }

    @Test
    fun `audit level is configurable`() = runBlocking {
        val loggerName = "test.jul.sink.audit"
        val logger = Logger.getLogger(loggerName)
        logger.level = Level.ALL
        val recorder = RecordingJulHandler()
        logger.addHandler(recorder)
        try {
            JulLogSink(loggerName = loggerName, auditLevel = Level.WARNING)
                .write(event(LogLevel.AUDIT))

            assertEquals(Level.WARNING, recorder.records.single().level)
        } finally {
            logger.removeHandler(recorder)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank logger names are rejected`() {
        JulLogSink(loggerName = " ")
    }
}

class JulLogHandlerTest {

    private fun logger(dispatch: DispatchMode = DispatchMode.INLINE): Pair<ILogger, TestLogSink> {
        val sink = TestLogSink()
        val logger = ILogger.create {
            sink(sink)
            clock = FixedClock(1_700_000_000_000)
            idGenerator = SequentialIdGenerator()
            this.dispatch = DispatchMode.INLINE
        }
        return logger to sink
    }

    @Test
    fun `forwards a jul record as a structured event`() {
        val (logger, sink) = logger()
        val julLogger = Logger.getLogger("acme.legacy.billing")
        julLogger.useParentHandlers = false
        julLogger.level = Level.ALL
        val handler = JulLogHandler(
            logger = logger,
            actionPrefix = "log",
            dispatch = DispatchMode.INLINE,
        )
        julLogger.addHandler(handler)
        try {
            julLogger.log(Level.SEVERE, "charge failed for {0}", arrayOf("order-9"))

            val event = sink.events().single()
            assertEquals("log.acme.legacy.billing", event.action)
            assertEquals(LogLevel.ERROR, event.level)
            assertEquals("acme.legacy.billing", event.source)
            assertEquals("charge failed for {0}", event.metadata["message"])
            assertEquals("order-9", event.metadata["param.0"])
            assertEquals("acme.legacy.billing", event.metadata["logger"])
        } finally {
            julLogger.removeHandler(handler)
        }
    }

    @Test
    fun `preserves the original throwable as the event failure`() {
        val (logger, sink) = logger()
        val julLogger = Logger.getLogger("acme.legacy.orders")
        julLogger.useParentHandlers = false
        julLogger.level = Level.ALL
        val handler = JulLogHandler(logger, dispatch = DispatchMode.INLINE)
        julLogger.addHandler(handler)
        try {
            julLogger.log(Level.WARNING, "retrying", IllegalStateException("db down"))

            val failure = sink.events().single().failure
            assertNotNull(failure)
            assertEquals(IllegalStateException::class.java.name, failure?.errorType)
            assertEquals("db down", failure?.message)
        } finally {
            julLogger.removeHandler(handler)
        }
    }

    @Test
    fun `maps jul levels to ilogger levels`() {
        val (logger, sink) = logger()
        val julLogger = Logger.getLogger("acme.legacy.levels")
        julLogger.useParentHandlers = false
        julLogger.level = Level.ALL
        val handler = JulLogHandler(logger, dispatch = DispatchMode.INLINE)
        julLogger.addHandler(handler)
        try {
            listOf(Level.FINEST, Level.FINER, Level.FINE, Level.CONFIG, Level.INFO, Level.WARNING, Level.SEVERE)
                .forEach { julLogger.log(it, "line") }

            assertEquals(
                listOf(
                    LogLevel.TRACE,
                    LogLevel.TRACE,
                    LogLevel.DEBUG,
                    LogLevel.DEBUG,
                    LogLevel.INFO,
                    LogLevel.WARN,
                    LogLevel.ERROR,
                ),
                sink.events().map { it.level },
            )
        } finally {
            julLogger.removeHandler(handler)
        }
    }

    @Test
    fun `ignores bridge loggers and configured prefixes`() {
        val (logger, sink) = logger()
        val julLogger = Logger.getLogger("noisy.library")
        julLogger.useParentHandlers = false
        julLogger.level = Level.ALL
        val handler = JulLogHandler(
            logger = logger,
            ignoredLoggerPrefixes = setOf("noisy", JulLogSink.BRIDGE_LOGGER_PREFIX),
            dispatch = DispatchMode.INLINE,
        )
        julLogger.addHandler(handler)
        try {
            julLogger.info("filtered")
            Logger.getLogger(JulLogSink.BRIDGE_LOGGER_PREFIX + ".events").info("also filtered")

            assertTrue(sink.events().isEmpty())
        } finally {
            julLogger.removeHandler(handler)
        }
    }

    @Test
    fun `both bridges together do not recurse`() {
        val sink = TestLogSink()
        val bridgeLogger = ILogger.create {
            sink(sink)
            sink(JulLogSink())
            clock = FixedClock(1_700_000_000_000)
            idGenerator = SequentialIdGenerator()
            dispatch = DispatchMode.INLINE
        }
        val julBridge = Logger.getLogger(JulLogSink.BRIDGE_LOGGER_PREFIX + ".events")
        julBridge.useParentHandlers = false
        julBridge.level = Level.ALL
        val handler = JulLogHandler(bridgeLogger, dispatch = DispatchMode.INLINE)
        julBridge.addHandler(handler)
        try {
            bridgeLogger.recordBlocking("task.created", dispatch = DispatchMode.INLINE)

            assertEquals(1, sink.count())

            val thirdParty = Logger.getLogger("acme.legacy.recursion")
            thirdParty.useParentHandlers = false
            thirdParty.level = Level.ALL
            thirdParty.addHandler(handler)
            try {
                thirdParty.info("third-party line")
            } finally {
                thirdParty.removeHandler(handler)
            }

            assertEquals(2, sink.count())
            assertEquals("jul.acme.legacy.recursion", sink.last()?.action)
        } finally {
            julBridge.removeHandler(handler)
        }
    }

    @Test
    fun `handler flush drains the logger without throwing`() {
        val (logger, _) = logger()
        JulLogHandler(logger).flush()
    }
}
