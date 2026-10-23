package io.ilogger.serialization

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.ActorType
import io.ilogger.core.FieldChange
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.Outcome
import io.ilogger.core.sink.EventCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
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

    /**
     * Parses one JSON line produced by [encode] back into a [LogEvent] — the
     * inverse projection of [toJsonElement]. Required members: `id`,
     * `timestampMillis` and `action`; `level` defaults to `AUDIT`,
     * `actionVersion` to 1 and `outcome` to SUCCESS. Metadata values come back
     * as plain Kotlin values (`null`, `String`, `Boolean`, `Long`, `Double`,
     * `Map<String, Any?>`, `List<Any?>`) — the same shapes the canonical chain
     * input renders, so a hash-chained stream (`HashChainSink` /
     * `TrailVerifier` in ilogger-core) verifies after a JSONL round trip.
     *
     * The projection is lossy where [encode] already is: `cancelled` failures
     * decode as regular failures, and containers deeper than the codec's
     * structure depth were written as the `"<max-depth>"` marker.
     *
     * @throws IllegalArgumentException when the line is not valid JSON, not a
     *   JSON object, or misses a required member (`id`, `timestampMillis`,
     *   `action`, `change.field`, `failure.errorType`).
     */
    public fun decode(line: String): LogEvent {
        val root = try {
            Json.parseToJsonElement(line)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("ilogger codec: line is not valid JSON")
        }
        val obj = root as? JsonObject
            ?: throw IllegalArgumentException("ilogger codec: line is not a JSON object")

        val id = obj.string("id")
            ?: throw IllegalArgumentException("ilogger codec: missing required member 'id'")
        val timestampMillis = obj.long("timestampMillis")
            ?: throw IllegalArgumentException("ilogger codec: missing required member 'timestampMillis'")
        val action = obj.string("action")
            ?: throw IllegalArgumentException("ilogger codec: missing required member 'action'")

        return LogEvent(
            id = id,
            timestampMillis = timestampMillis,
            level = obj.string("level")?.let { LogLevel.valueOf(it.uppercase()) } ?: LogLevel.AUDIT,
            action = action,
            actionVersion = obj.int("actionVersion") ?: 1,
            actor = obj.obj("actor")?.let(::decodeActor),
            entity = obj.string("entity"),
            entityId = obj.string("entityId"),
            source = obj.string("source"),
            category = obj.string("category"),
            correlationId = obj.string("correlationId"),
            causationId = obj.string("causationId"),
            metadata = obj.obj("metadata")?.mapValues { jsonToValue(it.value) } ?: emptyMap(),
            changes = obj.arr("changes")?.map { change ->
                val c = change.jsonObject
                FieldChange(
                    field = c.string("field")
                        ?: throw IllegalArgumentException("ilogger codec: change is missing 'field'"),
                    before = c["before"]?.let { jsonToValue(it) },
                    after = c["after"]?.let { jsonToValue(it) },
                )
            } ?: emptyList(),
            outcome = obj.string("outcome")?.let { Outcome.valueOf(it.uppercase()) } ?: Outcome.SUCCESS,
            failure = obj.obj("failure")?.let { f ->
                LogFailure(
                    errorType = f.string("errorType")
                        ?: throw IllegalArgumentException("ilogger codec: failure is missing 'errorType'"),
                    message = f.string("message"),
                    rootCauseType = f.string("rootCauseType"),
                    stackSummary = f.arr("stackSummary")?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
            },
        )
    }

    private fun decodeActor(obj: JsonObject): Actor {
        val type = obj.string("type")?.let { ActorType.valueOf(it.uppercase()) } ?: ActorType.CUSTOM
        val id = obj.string("id")
        val name = obj.string("name")
        val attributes = obj.obj("attributes")?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        return when (type) {
            ActorType.USER -> Actor.user(
                id ?: throw IllegalArgumentException("ilogger codec: user actor is missing 'id'"),
                name,
                attributes,
            )
            ActorType.SYSTEM -> Actor.system(attributes)
            ActorType.WORKER -> Actor.worker(id, name, attributes)
            ActorType.AUTOMATION -> Actor.automation(id, name, attributes)
            ActorType.PLUGIN -> Actor.plugin(id, name, attributes)
            ActorType.SERVICE -> Actor.service(id, name, attributes)
            ActorType.ANONYMOUS -> Actor.anonymous()
            ActorType.CUSTOM -> Actor.custom(id, name, attributes)
        }
    }

    private fun jsonToValue(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { jsonToValue(it.value) }
        is JsonArray -> element.map { jsonToValue(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.longOrNull != null -> element.long
            else -> element.double
        }
    }

    private fun JsonObject.string(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive is JsonNull) null else primitive.content
    }

    private fun JsonObject.long(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive is JsonNull) null else primitive.longOrNull
    }

    private fun JsonObject.int(name: String): Int? = long(name)?.toInt()

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

    /**
     * Projects an arbitrary metadata value onto a JSON element deterministically.
     *
     * Canonical forms: scalars map directly; `Char`/`UUID`/`Instant`/`Duration`
     * render as their `toString()` strings; unsigned integers render as JSON
     * numbers; `BigDecimal`/`BigInteger` keep their exact `toString()` form;
     * arrays and one-level [Iterable]/[Map] containers render structurally
     * with a depth guard (deeper values render as [MAX_DEPTH_MARKER] — never
     * `toString()`, which could recurse through a cyclic or very deep
     * collection); value
     * classes have no runtime marker and degrade to `toString()` unless a
     * [ValueAdapter][io.ilogger.core.ValueAdapter] renders them. Anything else
     * degrades to `toString()` — the codec never throws on unexpected types.
     */
    private fun Any?.toJsonValue(depth: Int = 0): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Char -> JsonPrimitive(toString())
        is java.util.UUID -> JsonPrimitive(toString())
        is java.time.Instant -> JsonPrimitive(toString())
        is java.time.Duration -> JsonPrimitive(toString())
        is kotlin.UByte -> JsonPrimitive(this)
        is kotlin.UShort -> JsonPrimitive(this)
        is kotlin.UInt -> JsonPrimitive(this)
        is kotlin.ULong -> JsonPrimitive(this)
        is Map<*, *> ->
            if (depth >= MAX_STRUCTURE_DEPTH) JsonPrimitive(MAX_DEPTH_MARKER)
            else {
                buildJsonObject {
                    forEach { (key, value) -> put(key.toString(), value.toJsonValue(depth + 1)) }
                }
            }
        is Iterable<*> ->
            if (depth >= MAX_STRUCTURE_DEPTH) JsonPrimitive(MAX_DEPTH_MARKER)
            else buildJsonArray { forEach { add(it.toJsonValue(depth + 1)) } }
        is Array<*> ->
            if (depth >= MAX_STRUCTURE_DEPTH) JsonPrimitive(MAX_DEPTH_MARKER)
            else buildJsonArray { forEach { add(it.toJsonValue(depth + 1)) } }
        is IntArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is LongArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is ShortArray -> buildJsonArray { forEach { add(JsonPrimitive(it.toInt())) } }
        is ByteArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is DoubleArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is FloatArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is BooleanArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is CharArray -> buildJsonArray { forEach { add(JsonPrimitive(it.toString())) } }
        is UIntArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is ULongArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is UShortArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        is UByteArray -> buildJsonArray { forEach { add(JsonPrimitive(it)) } }
        else -> JsonPrimitive(toString())
    }

    private const val MAX_STRUCTURE_DEPTH = 2

    /**
     * Rendered in place of a [Map]/[Iterable]/array nested deeper than the
     * codec's structure depth. Never calls `toString()` on the container: a
     * cyclic or pathologically deep collection would otherwise recurse without
     * bound and overflow the stack.
     */
    public const val MAX_DEPTH_MARKER: String = "<max-depth>"
}
