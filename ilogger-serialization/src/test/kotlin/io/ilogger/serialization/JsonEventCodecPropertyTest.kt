package io.ilogger.serialization

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.FieldChange
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.Outcome
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic property-style coverage for [JsonEventCodec]: seeded random
 * events (rather than a property-testing framework dependency) must always
 * produce one parseable JSON line and preserve the fields consumers rely on.
 */
class JsonEventCodecPropertyTest {

    private val random = Random(20261022L)

    private val fragments = listOf(
        "a", "Z", "0", " ", "-", "_", ".", ":", "\"", "\\", "/",
        "\n", "\r", "\t", "\u0000", "\u0001", "\u001f", "\u007f",
        "é", "ñ", "漢", "🚀", "{", "}", "[", "]",
    )

    private fun randomText(maxLength: Int = 16, allowBlank: Boolean = true): String {
        val length = if (allowBlank) {
            random.nextInt(0, maxLength + 1)
        } else {
            random.nextInt(1, maxLength + 1)
        }
        return buildString { repeat(length) { append(fragments.random(random)) } }
    }

    private fun randomEvent(index: Int): LogEvent {
        val outcome = Outcome.entries.random(random)
        val actor = if (random.nextBoolean()) {
            Actor.user(
                id = "actor-$index-${random.nextInt(1_000_000)}",
                name = randomText(8).ifBlank { null },
                attributes = if (random.nextBoolean()) mapOf("tenant" to randomText(8)) else emptyMap(),
            )
        } else {
            null
        }
        return LogEvent(
            id = "property-$index-${random.nextInt(1_000_000)}",
            timestampMillis = random.nextLong(0, 2_000_000_000_000),
            level = LogLevel.entries.random(random),
            action = "property.${index}.${randomText(8)}",
            actionVersion = random.nextInt(1, 6),
            actor = actor,
            entity = if (random.nextBoolean()) randomText(8) else null,
            entityId = if (random.nextBoolean()) randomText(8) else null,
            metadata = List(random.nextInt(0, 6)) { key ->
                "key-$key-${randomText(4)}" to randomMetadataValue()
            }.toMap(),
            changes = List(random.nextInt(0, 4)) { change ->
                FieldChange(
                    field = "field-$change-${randomText(4)}",
                    before = randomMetadataValue(),
                    after = randomMetadataValue(),
                )
            },
            source = if (random.nextBoolean()) randomText(8) else null,
            category = if (random.nextBoolean()) randomText(8) else null,
            correlationId = if (random.nextBoolean()) randomText(12) else null,
            causationId = if (random.nextBoolean()) randomText(12) else null,
            outcome = outcome,
            failure = if (outcome == Outcome.FAILURE) {
                LogFailure.from(IllegalStateException("failure ${randomText(12)}"))
            } else {
                null
            },
        )
    }

    private fun randomMetadataValue(): Any? = when (random.nextInt(10)) {
        0 -> null
        1 -> random.nextBoolean()
        2 -> random.nextInt()
        3 -> random.nextLong()
        4 -> random.nextDouble()
        5 -> randomText()
        6 -> UUID.randomUUID()
        7 -> Instant.ofEpochMilli(random.nextLong(0, 2_000_000_000_000))
        8 -> listOf(randomText(6), random.nextInt())
        else -> mapOf("nested" to mapOf("value" to randomText(6)))
    }

    @Test
    fun `random events encode to one parseable line and preserve required fields`() {
        repeat(300) { index ->
            val event = randomEvent(index)

            val encoded = JsonEventCodec.encode(event)
            assertFalse("encoded output must not contain a raw newline", encoded.contains('\n'))
            assertFalse("encoded output must not contain a raw carriage return", encoded.contains('\r'))

            val json = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(event.id, json.getValue("id").jsonPrimitive.content)
            assertEquals(event.timestampMillis, json.getValue("timestampMillis").jsonPrimitive.content.toLong())
            assertEquals(event.level.name, json.getValue("level").jsonPrimitive.content)
            assertEquals(event.action, json.getValue("action").jsonPrimitive.content)
            assertEquals(event.actionVersion, json.getValue("actionVersion").jsonPrimitive.content.toInt())
            assertEquals(event.outcome.name.lowercase(), json.getValue("outcome").jsonPrimitive.content)
            assertEquals(event.actor?.id, json.getStringOrNull("actor", "id"))
            assertEquals(event.entity, json.getStringOrNull("entity"))
            assertEquals(event.entityId, json.getStringOrNull("entityId"))
            assertEquals(event.source, json.getStringOrNull("source"))
            assertEquals(event.category, json.getStringOrNull("category"))
            assertEquals(event.correlationId, json.getStringOrNull("correlationId"))
            assertEquals(event.causationId, json.getStringOrNull("causationId"))
            assertEquals(event.metadata.isEmpty(), json["metadata"] == null)
            assertEquals(event.changes.isEmpty(), json["changes"] == null)
            assertEquals(event.failure == null, json["failure"] == null)

            val metadata = json["metadata"]?.jsonObject
            if (metadata != null) {
                assertEquals(event.metadata.keys, metadata.keys)
                event.metadata.forEach { (key, value) ->
                    if (value is String) {
                        assertEquals(value, (metadata.getValue(key) as JsonPrimitive).content)
                    }
                }
            }

            assertEquals("encoding must be deterministic", encoded, JsonEventCodec.encode(event))
        }
    }

    @Test
    fun `tricky strings and keys round-trip exactly`() {
        val tricky = listOf(
            "",
            "plain",
            "quote:\" backslash:\\ slash:/",
            "line\nbreak\r\n",
            "tab\tand\u0000nul",
            "control:\u0001\u001f\u007f",
            "unicode: é ñ 漢字 🚀",
            "json-looking: {\"a\":1}",
        )
        val event = LogEvent(
            id = "tricky",
            timestampMillis = 1,
            level = LogLevel.AUDIT,
            action = "tricky.strings",
            metadata = tricky.mapIndexed { index, value -> "key-$index-$value" to value }.toMap(),
        )

        val metadata = Json.parseToJsonElement(JsonEventCodec.encode(event))
            .jsonObject.getValue("metadata").jsonObject

        assertEquals(tricky.size, metadata.size)
        tricky.forEachIndexed { index, value ->
            val key = "key-$index-$value"
            assertEquals(value, (metadata.getValue(key) as JsonPrimitive).content)
        }
    }

    @Test
    fun `arbitrary metadata values never make the codec throw`() {
        val values = listOf(
            "string",
            null,
            -0.0,
            Pair("a", "b"),
            OpaqueValue(),
            intArrayOf(1, 2, 3),
            BigDecimal("19.99"),
            BigInteger("123456789012345678901234567890"),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ofHours(2),
            listOf(listOf(listOf("deep"))),
            mapOf("nested" to mapOf("deeper" to listOf(1, 2))),
        )

        repeat(100) { index ->
            val event = LogEvent(
                id = "opaque-$index",
                timestampMillis = 1,
                level = LogLevel.AUDIT,
                action = "opaque.values",
                metadata = values.mapIndexed { valueIndex, value -> "value-$valueIndex" to value }.toMap(),
            )

            val encoded = JsonEventCodec.encode(event)
            assertFalse(encoded.contains('\n'))
            assertTrue(Json.parseToJsonElement(encoded) is JsonObject)
        }
    }

    @Test
    fun `random nested structures always produce valid JSON`() {
        repeat(200) { index ->
            val event = LogEvent(
                id = "nested-$index",
                timestampMillis = 1,
                level = LogLevel.AUDIT,
                action = "nested.values",
                metadata = mapOf(
                    "value" to List(random.nextInt(0, 4)) {
                        if (random.nextBoolean()) randomText(6) else listOf(random.nextInt(), random.nextBoolean())
                    },
                ),
            )

            val json = Json.parseToJsonElement(JsonEventCodec.encode(event)).jsonObject
            assertTrue(json["metadata"] is JsonObject)
        }
    }

    private fun JsonObject.getStringOrNull(vararg path: String): String? {
        var current: JsonObject = this
        for (index in path.indices) {
            val element = current[path[index]] ?: return null
            if (index == path.lastIndex) return (element as? JsonPrimitive)?.content
            current = element as? JsonObject ?: return null
        }
        return null
    }

    private class OpaqueValue {
        override fun toString(): String = "opaque\n\"value\""
    }
}
