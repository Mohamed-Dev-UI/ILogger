package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.sink.ConsoleLogSink
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ILoggerDefaultsTest {

    private data class Call(
        val action: String,
        val level: LogLevel,
        val entity: String?,
        val entityId: String?,
        val actor: Actor?,
        val outcome: Outcome,
        val failure: Throwable?,
        val metadata: Map<String, Any?>,
        val changes: List<FieldChange>,
        val source: String?,
        val category: String?,
        val actionVersion: Int,
        val dispatch: DispatchMode,
        val sampling: Double,
    )

    private class MinimalLogger(
        override var enabled: Boolean = true,
    ) : ILogger {
        val suspendCalls = ArrayList<Call>()
        val nowCalls = ArrayList<Call>()
        val messages = ArrayList<String>()
        var flushes = 0
        var closes = 0

        override fun isEnabled(level: LogLevel): Boolean = enabled && level != LogLevel.TRACE

        override fun trace(message: String, vararg fields: Pair<String, Any?>) {
            messages += "trace:$message"
        }

        override fun debug(message: String, vararg fields: Pair<String, Any?>) {
            messages += "debug:$message"
        }

        override fun info(message: String, vararg fields: Pair<String, Any?>) {
            messages += "info:$message"
        }

        override fun warn(message: String, vararg fields: Pair<String, Any?>) {
            messages += "warn:$message"
        }

        override fun error(message: String, vararg fields: Pair<String, Any?>) {
            messages += "error:$message"
        }

        override suspend fun record(
            action: String,
            level: LogLevel,
            entity: String?,
            entityId: String?,
            actor: Actor?,
            outcome: Outcome,
            failure: Throwable?,
            metadata: Map<String, Any?>,
            changes: List<FieldChange>,
            source: String?,
            category: String?,
            actionVersion: Int,
            dispatch: DispatchMode,
            sampling: Double,
        ) {
            suspendCalls += Call(
                action, level, entity, entityId, actor, outcome, failure, metadata, changes,
                source, category, actionVersion, dispatch, sampling,
            )
        }

        override fun recordNow(
            action: String,
            level: LogLevel,
            entity: String?,
            entityId: String?,
            actor: Actor?,
            outcome: Outcome,
            failure: Throwable?,
            metadata: Map<String, Any?>,
            changes: List<FieldChange>,
            source: String?,
            category: String?,
            actionVersion: Int,
            dispatch: DispatchMode,
            sampling: Double,
        ) {
            nowCalls += Call(
                action, level, entity, entityId, actor, outcome, failure, metadata, changes,
                source, category, actionVersion, dispatch, sampling,
            )
        }

        override fun flush() {
            flushes += 1
        }

        override fun close() {
            closes += 1
        }
    }

    private val sampleFailure = IllegalStateException("boom")
    private val sampleActor = Actor.user("u1", name = "Grace")

    private fun fullDraft() = LogEventDraft(
        action = "task.created",
        level = LogLevel.WARN,
        entity = "Task",
        entityId = "42",
        actor = sampleActor,
        outcome = Outcome.FAILURE,
        failure = sampleFailure,
        metadata = mapOf("key" to "value"),
        changes = listOf(FieldChange("title", "draft", "done")),
        source = "TaskRepository.insert",
        category = "tasks",
        actionVersion = 3,
        dispatch = DispatchMode.INLINE,
        sampling = 0.25,
    )

    private fun assertFullCall(call: Call) {
        assertEquals("task.created", call.action)
        assertEquals(LogLevel.WARN, call.level)
        assertEquals("Task", call.entity)
        assertEquals("42", call.entityId)
        assertSame(sampleActor, call.actor)
        assertEquals(Outcome.FAILURE, call.outcome)
        assertSame(sampleFailure, call.failure)
        assertEquals(mapOf("key" to "value"), call.metadata)
        assertEquals(listOf(FieldChange("title", "draft", "done")), call.changes)
        assertEquals("TaskRepository.insert", call.source)
        assertEquals("tasks", call.category)
        assertEquals(3, call.actionVersion)
        assertEquals(DispatchMode.INLINE, call.dispatch)
        assertEquals(0.25, call.sampling, 0.0)
    }

    private fun event(action: String = "task.created") = LogEvent(
        id = "id-$action",
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.AUDIT,
        action = action,
    )

    // ------------------------------------------------------------------
    // Default interface methods
    // ------------------------------------------------------------------

    @Test
    fun `record draft delegates every field to the parameter overload`() = runBlocking {
        val logger = MinimalLogger()

        logger.record(fullDraft())

        assertFullCall(logger.suspendCalls.single())
        assertTrue(logger.nowCalls.isEmpty())
    }

    @Test
    fun `recordNow draft delegates every field to the parameter overload`() {
        val logger = MinimalLogger()

        logger.recordNow(fullDraft())

        assertFullCall(logger.nowCalls.single())
        assertTrue(logger.suspendCalls.isEmpty())
    }

    @Test
    fun `recordBlocking defaults to recordNow and reports success`() {
        val logger = MinimalLogger()

        val accepted = logger.recordBlocking(fullDraft(), timeoutMillis = 0)

        assertTrue(accepted)
        assertFullCall(logger.nowCalls.single())
    }

    @Test
    fun `recordBlocking parameter overload forwards every field and ignores the timeout`() {
        val logger = MinimalLogger()

        val accepted = logger.recordBlocking(
            action = "task.created",
            level = LogLevel.WARN,
            entity = "Task",
            entityId = "42",
            actor = sampleActor,
            outcome = Outcome.FAILURE,
            failure = sampleFailure,
            metadata = mapOf("key" to "value"),
            changes = listOf(FieldChange("title", "draft", "done")),
            source = "TaskRepository.insert",
            category = "tasks",
            actionVersion = 3,
            dispatch = DispatchMode.INLINE,
            sampling = 0.25,
            timeoutMillis = 0,
        )

        assertTrue(accepted)
        assertFullCall(logger.nowCalls.single())
    }

    @Test
    fun `isLoggable mirrors isEnabled`() {
        val logger = MinimalLogger()

        assertTrue(logger.isLoggable(LogLevel.INFO, "any.action"))
        assertFalse(logger.isLoggable(LogLevel.TRACE, "any.action"))

        logger.enabled = false
        assertFalse(logger.isLoggable(LogLevel.AUDIT, "any.action"))
    }

    @Test
    fun `default health accessors track the enabled switch`() {
        val logger = MinimalLogger()

        assertTrue(logger.hasSinks())
        assertFalse(logger.isBacklogged())
        assertEquals(Int.MAX_VALUE, logger.availableCapacity())

        logger.enabled = false
        assertFalse(logger.hasSinks())
        assertEquals(0, logger.availableCapacity())
    }

    @Test
    fun `tryRecord respects the enabled switch and forwards admission`() {
        val logger = MinimalLogger(enabled = false)

        assertFalse(logger.tryRecord("task.dropped"))
        assertTrue(logger.nowCalls.isEmpty())

        logger.enabled = true
        assertTrue(logger.tryRecord("task.created", metadata = mapOf("key" to "value")))
        assertEquals("task.created", logger.nowCalls.single().action)
        assertEquals(mapOf("key" to "value"), logger.nowCalls.single().metadata)
    }

    @Test
    fun `tryRecord draft delegates every field`() {
        val logger = MinimalLogger()

        assertTrue(logger.tryRecord(fullDraft()))

        assertFullCall(logger.nowCalls.single())
    }

    @Test
    fun `recordOrDrop forwards both shapes without an admission result`() {
        val logger = MinimalLogger()

        logger.recordOrDrop("task.one")
        logger.recordOrDrop(LogEventDraft(action = "task.two", category = "tasks"))

        assertEquals(listOf("task.one", "task.two"), logger.nowCalls.map { it.action })
        assertEquals("tasks", logger.nowCalls[1].category)
    }

    @Test
    fun `flush timeout calls flush and returns an empty report`() {
        val logger = MinimalLogger()

        val report = logger.flush(timeoutMillis = 0)

        assertEquals(1, logger.flushes)
        assertFalse(report.timedOut)
        assertEquals(0L, report.accepted)
        assertEquals(0L, report.delivered)
        assertEquals(0, report.pending)
        assertEquals(0, report.inFlight)
        assertTrue(report.droppedByReason.isEmpty())
    }

    @Test
    fun `awaitIdle stats and extractors have documented defaults`() {
        val logger = MinimalLogger()

        assertTrue(logger.awaitIdle(timeoutMillis = 0))
        assertEquals(DeliveryStats.EMPTY, logger.stats())
        assertTrue(logger.extractors.isEmpty)
        assertNotSame(logger.extractors, logger.extractors)
    }

    @Test
    fun `closeAndReport defaults to close and an empty report`() {
        val logger = MinimalLogger()

        val report = logger.closeAndReport()

        assertEquals(1, logger.closes)
        assertFalse(report.timedOut)
        assertEquals(0L, report.accepted)
        assertEquals(0L, report.delivered)
        assertEquals(0, report.pending)
        assertEquals(0, report.inFlight)
        assertTrue(report.droppedByReason.isEmpty())
        assertTrue(report.sinkFailures.isEmpty())
    }

    // ------------------------------------------------------------------
    // Permanently disabled logger
    // ------------------------------------------------------------------

    @Test
    fun `disabled logger is a permanent no-op`() = runBlocking {
        val logger = ILogger.disabled
        assertSame(ILogger.disabled, logger)
        assertFalse(logger.enabled)
        assertFalse(logger.isEnabled(LogLevel.AUDIT))

        logger.trace("trace", "key" to 1)
        logger.debug("debug", "key" to 1)
        logger.info("info", "key" to 1)
        logger.warn("warn", "key" to 1)
        logger.error("error", "key" to 1)

        logger.record("task.created")
        logger.record(LogEventDraft(action = "task.created"))
        logger.recordNow("task.created")
        logger.recordNow(LogEventDraft(action = "task.created"))

        assertFalse(logger.recordBlocking("task.created"))
        assertFalse(logger.recordBlocking(LogEventDraft(action = "task.created")))
        assertFalse(logger.tryRecord("task.created"))
        assertFalse(logger.tryRecord(LogEventDraft(action = "task.created")))

        logger.recordOrDrop("task.created")
        logger.recordOrDrop(LogEventDraft(action = "task.created"))

        assertFalse(logger.hasSinks())
        assertFalse(logger.isBacklogged())
        assertEquals(0, logger.availableCapacity())
        assertTrue(logger.awaitIdle(timeoutMillis = 0))
        assertEquals(DeliveryStats.EMPTY, logger.stats())
        assertTrue(logger.extractors.isEmpty)

        logger.flush()
        assertFalse(logger.flush(timeoutMillis = 0).timedOut)
        val report = logger.closeAndReport()
        assertFalse(report.timedOut)
        assertTrue(report.sinkFailures.isEmpty())
        logger.close()
    }

    // ------------------------------------------------------------------
    // Builder DSL overloads
    // ------------------------------------------------------------------

    private class FactoryExtractor(val origin: String)
    private class ClassFactoryExtractor(val origin: String)
    private class InstanceExtractor(val origin: String)
    private class ClassInstanceExtractor(val origin: String)

    @Test
    fun `builder registers Class-based value adapters`() {
        val config = ILoggerBuilder().apply {
            valueAdapter(String::class.java) { it.uppercase() }
        }.buildConfig()

        assertEquals("ABC", config.valueAdapters.render("abc"))
        assertFalse(config.valueAdapters.isEmpty)
    }

    @Test
    fun `builder extractor overloads register factories and instances`() {
        val instance = InstanceExtractor("k-class-instance")
        val classInstance = ClassInstanceExtractor("class-instance")
        val config = ILoggerBuilder().apply {
            extractor(FactoryExtractor::class) { FactoryExtractor("k-class-factory") }
            extractor(ClassFactoryExtractor::class.java) { ClassFactoryExtractor("class-factory") }
            extractor(InstanceExtractor::class, instance)
            extractor(ClassInstanceExtractor::class.java, classInstance)
        }.buildConfig()

        assertEquals("k-class-factory", config.extractors.get(FactoryExtractor::class) { error("fallback") }.origin)
        assertEquals(
            "class-factory",
            config.extractors.get(ClassFactoryExtractor::class) { error("fallback") }.origin,
        )
        assertSame(instance, config.extractors.get(InstanceExtractor::class) { error("fallback") })
        assertSame(classInstance, config.extractors.get(ClassInstanceExtractor::class) { error("fallback") })
    }

    @Test
    fun `extractor require resolves registered factories and explains missing ones`() {
        val config = ILoggerBuilder().apply {
            extractor(FactoryExtractor::class) { FactoryExtractor("registered") }
        }.buildConfig()

        assertEquals("registered", config.extractors.require(FactoryExtractor::class).origin)

        val error = assertThrows(IllegalStateException::class.java) {
            config.extractors.require(ClassInstanceExtractor::class)
        }
        assertTrue(error.message!!.contains("no extractor is registered"))
        assertTrue(error.message!!.contains("inject = true"))
    }

    @Test
    fun `builder deadLetter LogSink adapter forwards events and lifecycle`() = runBlocking {
        val written = ArrayList<String>()
        var flushes = 0
        var closes = 0
        val sink = object : LogSink {
            override suspend fun write(event: LogEvent) {
                written += event.action
            }

            override suspend fun flush() {
                flushes += 1
            }

            override fun close() {
                closes += 1
            }
        }
        val deadLetter = ILoggerBuilder().apply { deadLetter(sink) }.buildConfig().deadLetterSink!!

        deadLetter.write(DroppedEvent(null, DropReason.SAMPLED))
        deadLetter.write(DroppedEvent(event("task.created"), DropReason.SINK_FAILURE))
        deadLetter.flush()
        deadLetter.close()

        assertEquals(listOf("task.created"), written)
        assertEquals(1, flushes)
        assertEquals(1, closes)
    }

    @Test
    fun `builder rejects invalid configuration values`() {
        val cases: List<Pair<String, ILoggerBuilder.() -> Unit>> = listOf(
            "bufferSize must be positive" to { bufferSize = 0 },
            "batchSize must be >= 1" to { batchSize = 0 },
            "flushIntervalMillis must be >= 0" to { flushIntervalMillis = -1 },
            "closeTimeoutMillis must be >= 0" to { closeTimeoutMillis = -1 },
            "retryMaxAttempts must be >= 0" to { retryMaxAttempts = -1 },
            "retryBaseBackoffMillis must be >= 0" to { retryBaseBackoffMillis = -1 },
            "sinkConcurrency must be >= 1" to { sinkConcurrency = 0 },
            "blockTimeoutMillis must be >= 0" to { blockTimeoutMillis = -1 },
            "strictMaxStringLength must be positive" to { strictMaxStringLength = 0 },
            "strictMaxEntries must be positive" to { strictMaxEntries = 0 },
            "strictMaxEventBytes must be positive" to { strictMaxEventBytes = 0 },
        )

        for ((message, configure) in cases) {
            val error = assertThrows(IllegalStateException::class.java) {
                ILoggerBuilder().apply(configure).buildConfig()
            }
            assertTrue("expected '$message' but got '${error.message}'", error.message!!.contains(message))
        }
    }

    // ------------------------------------------------------------------
    // Companion default logger
    // ------------------------------------------------------------------

    @Test
    fun `setDefault installs and clears the process default`() {
        val custom = MinimalLogger()
        try {
            ILogger.setDefault(custom)
            assertSame(custom, ILogger.default)
        } finally {
            ILogger.setDefault(null)
        }
    }

    @Test
    fun `configure creates an independent logger and installs it as default`() {
        val configured = ILogger.configure { }
        try {
            assertSame(configured, ILogger.default)
        } finally {
            ILogger.setDefault(null)
            configured.close()
        }
    }

    @Test
    fun `default lazily creates one console fallback and caches it`() {
        ILogger.setDefault(null)
        val first = ILogger.default
        try {
            val second = ILogger.default
            assertSame(first, second)
            assertTrue(first.enabled)
            assertTrue(first.hasSinks())
        } finally {
            ILogger.setDefault(null)
            first.close()
        }
    }

    // ------------------------------------------------------------------
    // LogSink defaults
    // ------------------------------------------------------------------

    @Test
    fun `LogSink default writeAll writes sequentially and lifecycle methods are no-ops`() = runBlocking {
        val written = ArrayList<String>()
        val sink: LogSink = object : LogSink {
            override suspend fun write(event: LogEvent) {
                written += event.action
            }
        }

        sink.writeAll(listOf(event("one"), event("two"), event("three")))
        sink.flush()
        sink.close()

        assertEquals(listOf("one", "two", "three"), written)
        assertFalse(sink is DirectLogSink)
        val direct: LogSink = RecordingSink()
        assertTrue(direct is DirectLogSink)
    }

    @Test
    fun `ConsoleLogSink default constructor writes to standard output`() {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(buffer, true, "UTF-8"))
            ConsoleLogSink().writeDirect(event("console.default"))
        } finally {
            System.setOut(original)
        }

        assertTrue(buffer.toString("UTF-8").contains("console.default"))
    }
}
