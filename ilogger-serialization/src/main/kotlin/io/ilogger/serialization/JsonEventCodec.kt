package io.ilogger.serialization

import io.ilogger.core.LogEvent
import io.ilogger.core.sink.EventCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Single-line JSON codec for [LogEvent] (one compact JSON object per line —
 * JSONL), built on kotlinx-serialization JSON primitives.
 *
 * Output shape (all keys stable, values typed):
 *
 * ```json
 * {"id":"e1","timestampMillis":1700000000000,"level":"AUDIT","action":"task.created",
 *  "actionVersion":1,"outcome":"success","actor":{"id":"u1","type":"user"},
 *  "entity":"Task","entityId":"42","source":"TaskDao.insert","correlationId":"c1",
 *  "metadata":{"title":"x"},"changes":[],"failure":{"errorType":"...",...}}
 * ```
 *
 * Optional members (`actor`, `entity`, `entityId`, `source`, `category`,
 * `correlationId`, `causationId`, `metadata`, `changes`, `failure`) are omitted
 * entirely when absent — receivers never receive half-invented defaults.
 *
 * Metadata values are projected onto JSON primitives: `null`/`String`/
 * `Boolean`/`Number` map directly; anything else degrades to its `toString()`
 * — the codec never throws on unexpected value types.
 */
public object JsonEventCodec : EventCodec {

    override fun encode(event: LogEvent): String = toJsonElement(event).toString()

    /**
     * The full event as a [JsonObject] — for consumers that post-process
     * before final serialization.
     */
    public fun toJsonElement(event: LogEvent): JsonObject = buildJsonObject {
        put("id", event.id)
        put("timestampMillis", event.timestampMillis)
        put("level", event.level.name)
        put("action", event.action)
        put("actionVersion", event.actionVersion)
        put("outcome", event.outcome.name.lowercase())

        event.actor?.let { actor ->
            put(
                "actor",
                buildJsonObject {
                    actor.id?.let { put("id", it) }
                    put("type", actor.type.name.lowercase())
                    actor.name?.let { put("name", it) }
                    if (actor.attributes.isNotEmpty()) {
                        put(
                            "attributes",
                            buildJsonObject {
                                actor.attributes.forEach { (key, value) -> put(key, value) }
                            },
                        )
                    }
                },
            )
        }

        event.entity?.let { put("entity", it) }
        event.entityId?.let { put("entityId", it) }
        event.correlationId?.let { put("correlationId", it) }
        event.causationId?.let { put("causationId", it) }
        event.source?.let { put("source", it) }
        event.category?.let { put("category", it) }

        if (event.metadata.isNotEmpty()) {
            put(
                "metadata",
                buildJsonObject {
                    event.metadata.forEach { (key, value) -> put(key, value.toJsonValue()) }
                },
            )
        }

        if (event.changes.isNotEmpty()) {
            put(
                "changes",
                buildJsonArray {
                    event.changes.forEach { change ->
                        add(
                            buildJsonObject {
                                put("field", change.field)
                                put("before", change.before.toJsonValue())
                                put("after", change.after.toJsonValue())
                            },
                        )
                    }
                },
            )
        }

        event.failure?.let { failure ->
            put(
                "failure",
                buildJsonObject {
                    put("errorType", failure.errorType)
                    failure.message?.let { put("message", it) }
                    failure.rootCauseType?.let { put("rootCauseType", it) }
                    put(
                        "stackSummary",
                        buildJsonArray { failure.stackSummary.forEach { add(JsonPrimitive(it)) } },
                    )
                },
            )
        }
    }

    /** Projects an arbitrary metadata value onto a JSON primitive safely. */
    private fun Any?.toJsonValue(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}
