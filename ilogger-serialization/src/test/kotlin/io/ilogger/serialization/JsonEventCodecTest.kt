package io.ilogger.serialization

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.FieldChange
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonEventCodecTest {

    private fun fullEvent(): LogEvent = LogEvent(
        id = "e1",
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.AUDIT,
        action = "task.created",
        actionVersion = 2,
        actor = Actor.user("u1", name = "Ada", attributes = mapOf("tenant" to "acme")),
        entity = "Task",
        entityId = "42",
        metadata = mapOf(
            "title" to "ship it",
            "priority" to 5,
            "verified" to false,
            "weird" to listOf("x", "y"), // degrades to toString()
            "absent" to null,
        ),
        changes = listOf(FieldChange(field = "title", before = "old", after = "new")),
        source = "TaskDao.insert",
        category = "workflows",
        correlationId = "c-1",
        causationId = "e0",
        outcome = Outcome.SUCCESS,
        failure = null,
    )

    @Test
    fun `encodes the full shape with stable keys and typed values`() {
        val json = Json.parseToJsonElement(JsonEventCodec.encode(fullEvent())).jsonObject

        assertEquals("e1", json.getString("id"))
        assertEquals("task.created", json.getString("action"))
        assertEquals(2, json.getInt("actionVersion"))
        assertEquals("success", json.getString("outcome"))
        assertEquals("Task", json.getString("entity"))
        assertEquals("42", json.getString("entityId"))
        assertEquals("TaskDao.insert", json.getString("source"))
        assertEquals("workflows", json.getString("category"))
        assertEquals("c-1", json.getString("correlationId"))
        assertEquals("e0", json.getString("causationId"))

        val actor = json["actor"]!!.jsonObject
        assertEquals("u1", actor.getString("id"))
        assertEquals("user", actor.getString("type"))
        assertEquals("Ada", actor.getString("name"))
        assertEquals("acme", actor["attributes"]!!.jsonObject.getString("tenant"))

        val metadata = json["metadata"]!!.jsonObject
        assertEquals("ship it", metadata.getString("title"))
        assertEquals(5, metadata.getInt("priority"))
        assertEquals(false, metadata.getBoolean("verified"))
        // Heterogeneous values degrade deterministically, never throw.
        assertEquals("[x, y]", (metadata["weird"] as JsonPrimitive).content)
        assertTrue(metadata["absent"] is JsonNull)

        val change = (json["changes"] as JsonArray).first().jsonObject
        assertEquals("title", change.getString("field"))
        assertEquals("old", change.getString("before"))
        assertEquals("new", change.getString("after"))
    }

    @Test
    fun `absent optional members are omitted not invented`() {
        val event = LogEvent(
            id = "e2",
            timestampMillis = 1L,
            level = LogLevel.INFO,
            action = "app.started",
        )
        val json: JsonObject = JsonEventCodec.toJsonElement(event)

        assertTrue(json.keys.toSet() == setOf("id", "timestampMillis", "level", "action", "actionVersion", "outcome"))
        assertEquals("success", json.getString("outcome")) // SUCCESS is the default outcome
        assertEquals("app.started", json.getString("action"))
    }

    @Test
    fun `failure payload is embedded`() {
        val error = IllegalStateException("db offline")
        val event = LogEvent(
            id = "e3",
            timestampMillis = 1L,
            level = LogLevel.ERROR,
            action = "task.failed",
            outcome = Outcome.FAILURE,
            failure = LogFailure.from(error),
        )
        val json = JsonEventCodec.toJsonElement(event)
        val failure = json["failure"]!!.jsonObject

        assertEquals("failure", json.getString("outcome"))
        assert(failure.getString("errorType").contains("IllegalStateException"))
        assertEquals("db offline", failure.getString("message"))
        // Retained frames are capped (LogFailure.DEFAULT_STACK_FRAMES), non-empty here.
        assertTrue((failure["stackSummary"] as JsonArray).isNotEmpty())
    }

    @Test
    fun `output is single line`() {
        val encoded = JsonEventCodec.encode(fullEvent())
        assertEquals(1, encoded.lines().size)
    }

    // -- small local helpers (avoid extra test deps) --------------------------------

    private fun JsonObject.getString(name: String): String = (this[name] as JsonPrimitive).content
    private fun JsonObject.getInt(name: String): Int = (this[name] as JsonPrimitive).content.toInt()
    private fun JsonObject.getBoolean(name: String): Boolean = (this[name] as JsonPrimitive).content.toBoolean()
}
