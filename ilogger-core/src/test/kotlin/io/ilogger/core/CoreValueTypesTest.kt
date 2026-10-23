package io.ilogger.core

import io.ilogger.annotations.LogLevel
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreValueTypesTest {

    private fun event(action: String = "task.created") = LogEvent(
        id = "e1",
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.AUDIT,
        action = action,
        actionVersion = 2,
        actor = Actor.user("u1", name = "Grace", attributes = mapOf("team" to "core")),
        entity = "Task",
        entityId = "42",
        metadata = mapOf("title" to "write docs"),
        changes = listOf(FieldChange("title", "draft", "write docs")),
        source = "TaskRepository.insert",
        category = "tasks",
        correlationId = "corr-1",
        causationId = "cause-1",
        outcome = Outcome.FAILURE,
        failure = LogFailure("java.lang.IllegalStateException", "boom", null, listOf("frame")),
    )

    @Test
    fun `LogEvent equality covers every field and is symmetric`() {
        val first = event()
        val same = first.copy()
        val otherAction = first.copy(action = "task.deleted")
        val otherVersion = first.copy(actionVersion = 3)
        val otherActor = first.copy(actor = null)
        val otherMetadata = first.copy(metadata = mapOf("title" to "other"))

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertEquals(first, first)
        assertFalse(first.equals("not an event"))
        assertNotEquals(first, otherAction)
        assertNotEquals(first, otherVersion)
        assertNotEquals(first, otherActor)
        assertNotEquals(first, otherMetadata)
        assertNotEquals(first, first.copy(timestampMillis = 1))
        assertNotEquals(first, first.copy(level = LogLevel.INFO))
        assertNotEquals(first, first.copy(entity = null))
        assertNotEquals(first, first.copy(entityId = null))
        assertNotEquals(first, first.copy(changes = emptyList()))
        assertNotEquals(first, first.copy(source = null))
        assertNotEquals(first, first.copy(category = null))
        assertNotEquals(first, first.copy(correlationId = null))
        assertNotEquals(first, first.copy(causationId = null))
        assertNotEquals(first, first.copy(outcome = Outcome.SUCCESS))
        assertNotEquals(first, first.copy(failure = null))
    }

    @Test
    fun `LogEvent toString renders every field`() {
        val rendered = event().toString()

        assertTrue(rendered.startsWith("LogEvent(id=e1"))
        listOf(
            "timestampMillis=1700000000000",
            "level=AUDIT",
            "action=task.created",
            "actionVersion=2",
            "actor=u1(Grace)",
            "entity=Task",
            "entityId=42",
            "metadata={title=write docs}",
            "changes=[title: draft → write docs]",
            "source=TaskRepository.insert",
            "category=tasks",
            "correlationId=corr-1",
            "causationId=cause-1",
            "outcome=FAILURE",
            "failure=java.lang.IllegalStateException(message=boom)",
        ).forEach { fragment -> assertTrue("missing '$fragment' in $rendered", rendered.contains(fragment)) }
        assertTrue(rendered.endsWith(")"))
    }

    @Test
    fun `schemaId carries non-default versions`() {
        assertEquals("task.created", event().copy(actionVersion = 1).schemaId)
        assertEquals("task.created@v2", event().schemaId)
    }

    @Test
    fun `Actor factories cover every type`() {
        val attributes = mapOf("region" to "eu")

        assertEquals(ActorType.USER, Actor.user("u1", attributes = attributes).type)
        assertEquals(ActorType.SYSTEM, Actor.system(attributes).type)
        assertEquals(ActorType.WORKER, Actor.worker("w1", attributes = attributes).type)
        assertEquals(ActorType.AUTOMATION, Actor.automation("a1", attributes = attributes).type)
        assertEquals(ActorType.PLUGIN, Actor.plugin("p1", attributes = attributes).type)
        assertEquals(ActorType.SERVICE, Actor.service("s1", attributes = attributes).type)
        assertEquals(ActorType.CUSTOM, Actor.custom("c1", attributes = attributes).type)

        val anonymous = Actor.anonymous()
        assertEquals(ActorType.ANONYMOUS, anonymous.type)
        assertNull(anonymous.id)
        assertNull(anonymous.name)
        assertTrue(anonymous.attributes.isEmpty())
        assertEquals("anonymous", anonymous.toString())

        assertNull(Actor.worker().id)
        assertEquals(attributes, Actor.service("s1", attributes = attributes).attributes)
    }

    @Test
    fun `Actor factories reject blank ids`() {
        val factories: List<() -> Actor> = listOf(
            { Actor.user(" ") },
            { Actor.worker("") },
            { Actor.automation("  ") },
            { Actor.plugin("\t") },
            { Actor.service(" ") },
            { Actor.custom("") },
        )
        for (factory in factories) {
            val error = runCatching(factory).exceptionOrNull()
            assertTrue("expected IllegalArgumentException but got $error", error is IllegalArgumentException)
        }
    }

    @Test
    fun `Actor equality covers type and attributes`() {
        val actor = Actor.user("u1", name = "Grace", attributes = mapOf("team" to "core"))
        val same = Actor.user("u1", name = "Grace", attributes = mapOf("team" to "core"))
        val differentType = Actor.automation("u1", name = "Grace", attributes = mapOf("team" to "core"))
        val differentAttributes = Actor.user("u1", name = "Grace", attributes = emptyMap())

        assertEquals(actor, same)
        assertEquals(actor.hashCode(), same.hashCode())
        assertNotEquals(actor, differentType)
        assertNotEquals(actor, differentAttributes)
        assertFalse(actor.equals("u1"))
        assertEquals("u1(Grace)", actor.toString())
        assertEquals("u1", Actor.user("u1").toString())
    }

    @Test
    fun `copyWith keeps the actor type and replaces only the given fields`() {
        val original = Actor.service("s1", name = "billing", attributes = mapOf("region" to "eu"))
        val copy = original.copyWith(name = "ledger", attributes = emptyMap())

        assertEquals(ActorType.SERVICE, copy.type)
        assertEquals("s1", copy.id)
        assertEquals("ledger", copy.name)
        assertTrue(copy.attributes.isEmpty())
        assertSame(original.attributes, original.copyWith().attributes)
    }

    @Test
    fun `FieldChange equality hashing and rendering`() {
        val change = FieldChange("title", "draft", "done")
        val same = FieldChange("title", "draft", "done")

        assertEquals(change, same)
        assertEquals(change.hashCode(), same.hashCode())
        assertNotEquals(change, FieldChange("title", "draft", "other"))
        assertNotEquals(change, FieldChange("title", null, "done"))
        assertNotEquals(change, FieldChange("status", "draft", "done"))
        assertFalse(change.equals("title"))
        assertEquals("title: draft → done", change.toString())
        assertEquals("title: null → null", FieldChange("title", null, null).toString())
    }

    @Test
    fun `LogFailure captures root cause frames and cancellation`() {
        fun deepest(depth: Int): Nothing =
            if (depth == 0) {
                throw IllegalStateException("root")
            } else {
                try {
                    deepest(depth - 1)
                } catch (cause: IllegalStateException) {
                    throw IllegalStateException("level-$depth", cause)
                }
            }

        val error = runCatching { deepest(12) }.exceptionOrNull()!!
        val failure = LogFailure.from(error, maxStackFrames = 3)

        assertEquals("java.lang.IllegalStateException", failure.errorType)
        assertEquals("level-12", failure.message)
        assertEquals("java.lang.IllegalStateException", failure.rootCauseType)
        assertEquals(3, failure.stackSummary.size)
        assertFalse(failure.cancelled)
        assertFalse(failure.isCancellation)
        assertTrue(failure.toString().startsWith("java.lang.IllegalStateException(message=level-12)"))

        val cancellation = LogFailure.from(CancellationException("cancelled"))
        assertTrue(cancellation.cancelled)
        assertTrue(cancellation.isCancellation)
    }

    @Test
    fun `LogMasking hash is deterministic bounded and null preserving`() {
        assertNull(LogMasking.hash(null))
        val hash = LogMasking.hash("user@example.com")!!
        assertEquals(LogMasking.HASH_LENGTH, hash.length)
        assertEquals(hash, LogMasking.hash("user@example.com"))
        assertNotEquals(hash, LogMasking.hash("other@example.com"))
        assertTrue(hash.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `DeliveryStats exposes dropped totals and queue depth`() {
        val stats = DeliveryStats(
            accepted = 10,
            delivered = 7,
            pending = 2,
            inFlight = 1,
            droppedByReason = mapOf(DropReason.OVERFLOW_NEWEST to 2L, DropReason.SINK_FAILURE to 1L),
        )

        assertEquals(3L, stats.dropped)
        assertEquals(2, stats.queueDepth)
        assertEquals(0L, DeliveryStats.EMPTY.dropped)
    }

    @Test
    fun `DeadLetterSink of an ordinary sink ignores null events and delegates lifecycle`() = runBlocking {
        val written = ArrayList<LogEvent>()
        var flushed = 0
        var closed = 0
        val ordinary = object : LogSink {
            override suspend fun write(event: LogEvent) {
                written += event
            }

            override suspend fun flush() {
                flushed += 1
            }

            override fun close() {
                closed += 1
            }
        }
        val deadLetter = DeadLetterSink.of(ordinary)
        val cause = IllegalStateException("lost")

        deadLetter.write(DroppedEvent(null, DropReason.SAMPLED))
        deadLetter.write(DroppedEvent(event(), DropReason.SINK_FAILURE, cause))
        deadLetter.flush()
        deadLetter.close()

        assertEquals(listOf("task.created"), written.map { it.action })
        assertEquals(1, flushed)
        assertEquals(1, closed)
    }

    @Test
    fun `LogContext toString includes identity correlation causation and attributes`() {
        val context = LogContext(
            actor = Actor.user("u1"),
            correlationId = "corr-1",
            causationId = "cause-1",
            attributes = mapOf("tenant" to "acme"),
        )

        assertEquals(
            "<LogContext actor=u1 correlation=corr-1 causation=cause-1 attributes={tenant=acme}>",
            context.toString(),
        )
    }
}
