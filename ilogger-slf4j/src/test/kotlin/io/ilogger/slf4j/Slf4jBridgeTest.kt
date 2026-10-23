package io.ilogger.slf4j

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ilogger.annotations.DispatchMode
import io.ilogger.core.FixedClock
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.annotations.LogLevel
import io.ilogger.testing.SequentialIdGenerator
import io.ilogger.testing.TestLogSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC

private class RecordingAppender : AppenderBase<ILoggingEvent>() {
    val events: MutableList<ILoggingEvent> = mutableListOf()

    override fun append(eventObject: ILoggingEvent) {
        events += eventObject
    }
}

private fun newContext(): LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

private fun event(
    level: LogLevel,
    action: String = "task.created",
): LogEvent = LogEvent(
    id = "e-${level.name}",
    timestampMillis = 1_700_000_000_000,
    level = level,
    action = action,
    entity = "Task",
    entityId = "42",
    actor = io.ilogger.core.Actor.user("user-1"),
    correlationId = "corr-1",
    causationId = "cause-1",
    metadata = mapOf("task.title" to "Write docs"),
    source = "TaskRepository.insert",
)

class Slf4jLogSinkTest {

    @Test
    fun `maps every level and attaches structured key values`() = runBlocking {
        val context = newContext()
        val logger = context.getLogger("test.slf4j.sink")
        logger.level = Level.TRACE
        logger.isAdditive = false
        val recorder = RecordingAppender()
        recorder.context = context
        recorder.start()
        logger.addAppender(recorder)
        try {
            val sink = Slf4jLogSink(loggerName = "test.slf4j.sink", factory = context)
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
                listOf(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.INFO),
                recorder.events.map { it.level },
            )
            val first = recorder.events.first()
            val keyValues = first.keyValuePairs.associate { it.key to it.value }
            assertEquals("e-TRACE", keyValues["ilogger.eventId"])
            assertEquals("task.created", keyValues["ilogger.action"])
            assertEquals("Task", keyValues["ilogger.entity"])
            assertEquals("42", keyValues["ilogger.entityId"])
            assertEquals("user-1", keyValues["ilogger.actor"])
            assertEquals("corr-1", keyValues["ilogger.correlationId"])
            assertEquals("cause-1", keyValues["ilogger.causationId"])
            assertTrue(first.formattedMessage.contains("task.created"))
        } finally {
            logger.detachAppender(recorder)
        }
    }

    @Test
    fun `audit level is configurable`() = runBlocking {
        val context = newContext()
        val logger = context.getLogger("test.slf4j.sink.audit")
        logger.level = Level.TRACE
        logger.isAdditive = false
        val recorder = RecordingAppender()
        recorder.context = context
        recorder.start()
        logger.addAppender(recorder)
        try {
            Slf4jLogSink(loggerName = "test.slf4j.sink.audit", auditLevel = org.slf4j.event.Level.WARN, factory = context)
                .write(event(LogLevel.AUDIT))

            assertEquals(Level.WARN, recorder.events.single().level)
        } finally {
            logger.detachAppender(recorder)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank logger names are rejected`() {
        Slf4jLogSink(loggerName = "", factory = newContext())
    }
}

class ILoggerAppenderTest {

    private fun ilogger(): Pair<ILogger, TestLogSink> {
        val sink = TestLogSink()
        val logger = ILogger.create {
            sink(sink)
            clock = FixedClock(1_700_000_000_000)
            idGenerator = SequentialIdGenerator()
            dispatch = DispatchMode.INLINE
        }
        return logger to sink
    }

    private fun attach(
        context: LoggerContext,
        loggerName: String,
        appender: ILoggerAppender,
    ): ch.qos.logback.classic.Logger {
        val logger = context.getLogger(loggerName)
        logger.level = Level.TRACE
        logger.isAdditive = false
        appender.context = context
        appender.start()
        logger.addAppender(appender)
        return logger
    }

    @Test
    fun `forwards level message mdc and throwable`() {
        val context = newContext()
        val (ilogger, sink) = ilogger()
        val logger = attach(context, "acme.legacy.billing", ILoggerAppender(ilogger, dispatch = DispatchMode.INLINE))
        try {
            MDC.put("tenant", "acme")
            logger.atInfo().addKeyValue("orderId", "order-9").log("charged {}", "order-9")
            logger.error("charge failed", IllegalStateException("card declined"))

            val events = sink.events()
            assertEquals(2, events.size)

            val info = events[0]
            assertEquals("log.acme.legacy.billing", info.action)
            assertEquals(LogLevel.INFO, info.level)
            assertEquals("acme.legacy.billing", info.source)
            assertEquals("charged order-9", info.metadata["message"])
            assertEquals("acme", info.metadata["mdc.tenant"])
            assertEquals("order-9", info.metadata["kv.orderId"])
            assertNull(info.failure)

            val error = events[1]
            assertEquals(LogLevel.ERROR, error.level)
            assertNotNull(error.failure)
            assertEquals(IllegalStateException::class.java.name, error.failure?.errorType)
            assertEquals("card declined", error.failure?.message)
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `maps logback levels to ilogger levels`() {
        val context = newContext()
        val (ilogger, sink) = ilogger()
        val logger = attach(context, "acme.legacy.levels", ILoggerAppender(ilogger, dispatch = DispatchMode.INLINE))
        try {
            logger.trace("line")
            logger.debug("line")
            logger.info("line")
            logger.warn("line")
            logger.error("line")

            assertEquals(
                listOf(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
                sink.events().map { it.level },
            )
        } finally {
        }
    }

    @Test
    fun `ignores bridge logger names and can opt out of mdc`() {
        val context = newContext()
        val (ilogger, sink) = ilogger()
        val appender = ILoggerAppender(
            logger = ilogger,
            ignoredLoggerPrefixes = setOf("noisy", Slf4jLogSink.BRIDGE_LOGGER_PREFIX),
            includeMdc = false,
            dispatch = DispatchMode.INLINE,
        )
        val bridge = attach(context, Slf4jLogSink.BRIDGE_LOGGER_PREFIX + ".events", appender)
        val ignored = attach(context, "noisy.library", appender)
        try {
            MDC.put("tenant", "acme")
            bridge.info("bridge line")
            ignored.info("noisy line")
            context.getLogger("quiet.app").info("quiet line")

            assertTrue(sink.events().isEmpty())

            val quiet = context.getLogger("quiet.app")
            quiet.level = Level.TRACE
            quiet.isAdditive = false
            quiet.addAppender(appender)
            quiet.info("kept")

            val kept = sink.events().single()
            assertEquals("log.quiet.app", kept.action)
            assertNull(kept.metadata["mdc.tenant"])
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `both bridges together do not recurse`() {
        val context = newContext()
        val sink = TestLogSink()
        val bridgeLogger = ILogger.create {
            sink(sink)
            sink(Slf4jLogSink(factory = context, loggerName = Slf4jLogSink.BRIDGE_LOGGER_PREFIX + ".events"))
            clock = FixedClock(1_700_000_000_000)
            idGenerator = SequentialIdGenerator()
            dispatch = DispatchMode.INLINE
        }
        val appender = ILoggerAppender(bridgeLogger, dispatch = DispatchMode.INLINE)
        val bridge = attach(context, Slf4jLogSink.BRIDGE_LOGGER_PREFIX + ".events", appender)
        val thirdParty = attach(context, "acme.legacy.recursion", appender)
        try {
            bridgeLogger.recordBlocking("task.created", dispatch = DispatchMode.INLINE)
            assertEquals(1, sink.count())

            thirdParty.info("legacy line")

            assertEquals(2, sink.count())
            assertEquals("log.acme.legacy.recursion", sink.last()?.action)
        } finally {
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank action prefix is rejected`() {
        val (ilogger, _) = ilogger()
        ILoggerAppender(ilogger, actionPrefix = " ")
    }
}
