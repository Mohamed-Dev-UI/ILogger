package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LogEventDraftTest {

    @Test
    fun `record accepts a draft and preserves every field`() = runBlocking {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.ASYNC
        }
        logger.use {
            val draft = LogEventDraft(
                action = "task.created",
                level = LogLevel.AUDIT,
                entity = "Task",
                entityId = "42",
                actor = Actor.user("u1"),
                outcome = Outcome.FAILURE,
                failure = IllegalStateException("boom"),
                metadata = mapOf("title" to "Write docs"),
                changes = listOf(FieldChange("title", "old", "new")),
                source = "DraftTest",
                category = "workflows",
                actionVersion = 2,
                dispatch = DispatchMode.ASYNC,
            )

            logger.record(draft)
            assertTrue(logger.awaitIdle(5_000))

            val event = sink.events.single()
            assertEquals("task.created", event.action)
            assertEquals("task.created@v2", event.schemaId)
            assertEquals(LogLevel.AUDIT, event.level)
            assertEquals("Task", event.entity)
            assertEquals("42", event.entityId)
            assertEquals(Actor.user("u1"), event.actor)
            assertEquals(Outcome.FAILURE, event.outcome)
            assertEquals("java.lang.IllegalStateException", event.failure!!.errorType)
            assertEquals(mapOf("title" to "Write docs"), event.metadata)
            assertEquals(listOf(FieldChange("title", "old", "new")), event.changes)
            assertEquals("DraftTest", event.source)
            assertEquals("workflows", event.category)
        }
    }

    @Test
    fun `recordNow and recordBlocking accept drafts`() {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
        }
        logger.use {
            logger.recordNow(LogEventDraft(action = "draft.now", dispatch = DispatchMode.INLINE))
            assertTrue(logger.recordBlocking(LogEventDraft(action = "draft.blocking"), timeoutMillis = 1_000))
        }
        assertEquals(listOf("draft.now", "draft.blocking"), sink.actions())
    }

    @Test
    fun `mutating source containers after record cannot alter the event`() = runBlocking {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.ASYNC
        }
        logger.use {
            val metadata = mutableMapOf<String, Any?>("key" to "first")
            val changes = mutableListOf(FieldChange("title", 1, 2))

            logger.record(action = "mutation.check", metadata = metadata, changes = changes)
            metadata["key"] = "second"
            metadata["late"] = "entry"
            changes += FieldChange("extra", 3, 4)

            assertTrue(logger.awaitIdle(5_000))
            val event = sink.events.single()
            assertEquals(mapOf("key" to "first"), event.metadata)
            assertEquals(listOf(FieldChange("title", 1, 2)), event.changes)
        }
    }

    @Test
    fun `LogEvent copies and freezes metadata and changes`() {
        val metadata = mutableMapOf<String, Any?>("key" to "value")
        val changes = mutableListOf(FieldChange("title", "a", "b"))
        val event = LogEvent(
            id = "e1",
            timestampMillis = 1,
            level = LogLevel.AUDIT,
            action = "event.copy",
            metadata = metadata,
            changes = changes,
        )

        metadata["key"] = "changed"
        metadata["late"] = "entry"
        changes.clear()

        assertEquals(mapOf("key" to "value"), event.metadata)
        assertEquals(listOf(FieldChange("title", "a", "b")), event.changes)

        try {
            (event.metadata as MutableMap<String, Any?>)["new"] = 1
            fail("metadata must be unmodifiable")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
        try {
            (event.changes as MutableList<FieldChange>).add(FieldChange("x", 1, 2))
            fail("changes must be unmodifiable")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `drafts reject blank actions`() {
        LogEventDraft(action = " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `drafts reject non-positive schema versions`() {
        LogEventDraft(action = "draft.invalid", actionVersion = 0)
    }

    @Test
    fun `custom loggers may implement only the draft overload`() = runBlocking {
        val captured = ArrayList<LogEventDraft>()
        val logger = object : ILogger {
            override var enabled: Boolean = true

            override fun isEnabled(level: LogLevel): Boolean = true

            override fun trace(message: String, vararg fields: Pair<String, Any?>) {}
            override fun debug(message: String, vararg fields: Pair<String, Any?>) {}
            override fun info(message: String, vararg fields: Pair<String, Any?>) {}
            override fun warn(message: String, vararg fields: Pair<String, Any?>) {}
            override fun error(message: String, vararg fields: Pair<String, Any?>) {}

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
                fail("the 14-parameter overload must not be used when a draft overload is overridden")
            }

            override suspend fun record(draft: LogEventDraft) {
                captured += draft
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
                fail("the 14-parameter overload must not be used when a draft overload is overridden")
            }

            override fun flush() {}
            override fun close() {}
        }

        logger.record(LogEventDraft(action = "draft.only"))
        assertEquals("draft.only", captured.single().action)
    }
}
