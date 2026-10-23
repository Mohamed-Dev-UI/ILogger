@file:OptIn(ExperimentalUnsignedTypes::class)

package io.ilogger.core

import kotlin.math.min

/** How [EventLimits] handles a payload that exceeds a configured bound. */
public enum class LimitAction {
    /** Drop the event and report it through `onDrop` with [DropReason.STRICT_REJECT]. */
    REJECT,

    /**
     * Rewrite the event so it fits: oversized strings are shortened, collections
     * are cut to the configured entry count and a marker is appended. The event
     * is delivered and counted in [DeliveryStats.truncated].
     */
    TRUNCATE,
}

/**
 * Hard resource bounds enforced on every event built by the engine.
 *
 * Limits are opt-in (`ILogger.create { limits = EventLimits(...) }`) and run
 * after value adapters, before sampling and strict validation. They apply to
 * manual `record` calls, diagnostic calls and generated wrappers alike.
 *
 * Both policies traverse nested values lazily and stop at the bounds — they
 * never materialize an unbounded collection — so a hostile or accidental
 * payload cannot exhaust memory during admission. The action code itself is
 * never truncated: an action longer than [maxStringLength] rejects the event
 * under both policies, because shortening it would break the action registry.
 *
 * @param maxEventBytes approximate payload budget (string characters + 8 bytes
 *   per scalar) covering action/entity/source/category, actor, metadata,
 *   changes and failure details.
 * @param maxStringLength maximum length of every string (values, keys, ids,
 *   failure messages and frames).
 * @param maxEntries maximum entries in metadata, changes and each nested
 *   map/collection.
 * @param maxActorAttributes maximum entries in [Actor.attributes].
 * @param maxFailureFrames maximum retained stack frames.
 * @param action reject or truncate when a bound is exceeded.
 */
public class EventLimits(
    public val maxEventBytes: Int = 65_536,
    public val maxStringLength: Int = 1_024,
    public val maxEntries: Int = 64,
    public val maxActorAttributes: Int = 32,
    public val maxFailureFrames: Int = 32,
    public val action: LimitAction = LimitAction.REJECT,
) {
    init {
        require(maxEventBytes > 0) { "maxEventBytes must be positive, got $maxEventBytes" }
        require(maxStringLength > 0) { "maxStringLength must be positive, got $maxStringLength" }
        require(maxEntries >= 1) { "maxEntries must be >= 1, got $maxEntries" }
        require(maxActorAttributes >= 0) { "maxActorAttributes must be >= 0, got $maxActorAttributes" }
        require(maxFailureFrames >= 0) { "maxFailureFrames must be >= 0, got $maxFailureFrames" }
    }

    public companion object {
        /** Appended to every shortened string under [LimitAction.TRUNCATE]. */
        public const val TRUNCATION_MARKER: String = "…[truncated]"

        /**
         * Metadata key set to `true` on events rewritten by
         * [LimitAction.TRUNCATE], so consumers can detect lossy payloads without
         * inspecting every value.
         */
        public const val TRUNCATED_KEY: String = "ilogger.truncated"
    }
}

/** Applies [EventLimits] to one event's payload. */
internal object LimitsEnforcement {

    internal class Outcome(
        val accepted: Boolean,
        val truncated: Boolean,
        val violation: String?,
        val actor: Actor?,
        val entity: String? = null,
        val entityId: String? = null,
        val source: String? = null,
        val category: String? = null,
        val metadata: Map<String, Any?>,
        val changes: List<FieldChange>,
        val failure: LogFailure?,
    )

    fun apply(
        limits: EventLimits,
        action: String,
        actor: Actor?,
        entity: String?,
        entityId: String?,
        source: String?,
        category: String?,
        metadata: Map<String, Any?>,
        changes: List<FieldChange>,
        failure: LogFailure?,
    ): Outcome {
        if (action.length > limits.maxStringLength) {
            return rejected("action is ${action.length} characters; the limit is ${limits.maxStringLength}")
        }
        return if (limits.action == LimitAction.REJECT) {
            val violation = violation(
                limits, action, actor, entity, entityId, source, category, metadata, changes, failure,
            )
            if (violation != null) rejected(violation) else Outcome(
                accepted = true,
                truncated = false,
                violation = null,
                actor = actor,
                entity = entity,
                entityId = entityId,
                source = source,
                category = category,
                metadata = metadata,
                changes = changes,
                failure = failure,
            )
        } else {
            truncate(limits, actor, entity, entityId, source, category, metadata, changes, failure)
        }
    }

    private fun rejected(violation: String): Outcome = Outcome(
        accepted = false,
        truncated = false,
        violation = violation,
        actor = null,
        metadata = emptyMap(),
        changes = emptyList(),
        failure = null,
    )

    // ------------------------------------------------------------------
    // REJECT
    // ------------------------------------------------------------------

    private fun violation(
        limits: EventLimits,
        action: String,
        actor: Actor?,
        entity: String?,
        entityId: String?,
        source: String?,
        category: String?,
        metadata: Map<String, Any?>,
        changes: List<FieldChange>,
        failure: LogFailure?,
    ): String? {
        val budget = Budget(limits.maxEventBytes)
        checkString("action", action, limits, budget)?.let { return it }
        entity?.let { checkString("entity", it, limits, budget)?.let { error -> return error } }
        entityId?.let { checkString("entityId", it, limits, budget)?.let { error -> return error } }
        source?.let { checkString("source", it, limits, budget)?.let { error -> return error } }
        category?.let { checkString("category", it, limits, budget)?.let { error -> return error } }

        actor?.let {
            it.id?.let { id -> checkString("actor.id", id, limits, budget)?.let { error -> return error } }
            it.name?.let { name -> checkString("actor.name", name, limits, budget)?.let { error -> return error } }
            if (it.attributes.size > limits.maxActorAttributes) {
                return "actor.attributes has ${it.attributes.size} entries; the limit is ${limits.maxActorAttributes}"
            }
            for ((key, value) in it.attributes) {
                checkString("actor.attributes key", key, limits, budget)?.let { error -> return error }
                checkString("actor.attributes[$key]", value, limits, budget)?.let { error -> return error }
            }
        }

        if (metadata.size > limits.maxEntries) {
            return "metadata has ${metadata.size} entries; the limit is ${limits.maxEntries}"
        }
        for ((key, value) in metadata) {
            checkString("metadata key", key, limits, budget)?.let { return it }
            checkValue("metadata[$key]", value, 0, limits, budget)?.let { return it }
        }

        if (changes.size > limits.maxEntries) {
            return "changes has ${changes.size} entries; the limit is ${limits.maxEntries}"
        }
        for (change in changes) {
            checkString("changes.field", change.field, limits, budget)?.let { return it }
            checkValue("changes[${change.field}].before", change.before, 0, limits, budget)?.let { return it }
            checkValue("changes[${change.field}].after", change.after, 0, limits, budget)?.let { return it }
        }

        failure?.let {
            checkString("failure.errorType", it.errorType, limits, budget)?.let { error -> return error }
            it.message?.let { message -> checkString("failure.message", message, limits, budget)?.let { error -> return error } }
            it.rootCauseType?.let { type -> checkString("failure.rootCauseType", type, limits, budget)?.let { error -> return error } }
            if (it.stackSummary.size > limits.maxFailureFrames) {
                return "failure.stackSummary has ${it.stackSummary.size} frames; the limit is ${limits.maxFailureFrames}"
            }
            for ((index, frame) in it.stackSummary.withIndex()) {
                checkString("failure.stackSummary[$index]", frame, limits, budget)?.let { error -> return error }
            }
        }

        if (budget.overflowed) return "event payload exceeds the limit of ${limits.maxEventBytes} bytes"
        return null
    }

    private fun checkString(path: String, value: String, limits: EventLimits, budget: Budget): String? {
        if (value.length > limits.maxStringLength) {
            return "$path is ${value.length} characters; the limit is ${limits.maxStringLength}"
        }
        budget.add(value.length)
        return null
    }

    private fun checkValue(
        path: String,
        value: Any?,
        depth: Int,
        limits: EventLimits,
        budget: Budget,
    ): String? = when (value) {
        null -> null.also { budget.add(1) }
        is LogSafeValue -> null.also { budget.add(SCALAR_BYTES) }
        is String -> checkString(path, value, limits, budget)
        is Char -> null.also { budget.add(CHAR_BYTES) }
        is Boolean -> null.also { budget.add(BOOLEAN_BYTES) }
        is Byte, is Short, is Int, is Long, is Float, is Double,
        is UByte, is UShort, is UInt, is ULong,
        is java.util.UUID, is java.time.Instant, is java.time.Duration,
        is java.math.BigDecimal, is java.math.BigInteger,
        -> null.also { budget.add(SCALAR_BYTES) }
        is Enum<*> -> checkString("$path (enum)", value.name, limits, budget)
        is Map<*, *> -> {
            if (depth >= MAX_DEPTH) {
                "$path is nested deeper than $MAX_DEPTH levels"
            } else if (value.size > limits.maxEntries) {
                "$path has ${value.size} entries; the limit is ${limits.maxEntries}"
            } else {
                var violation: String? = null
                for ((key, nested) in value) {
                    if (key !is String) {
                        violation = "$path has a non-string key of type ${key?.let { it::class.java.name } ?: "null"}"
                        break
                    }
                    violation = checkString("$path key", key, limits, budget)
                    if (violation != null) break
                    violation = checkValue("$path[$key]", nested, depth + 1, limits, budget)
                    if (violation != null) break
                }
                violation
            }
        }
        is Iterable<*> -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is Array<*> -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is IntArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is LongArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is ShortArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is ByteArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is FloatArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is DoubleArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is BooleanArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is CharArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is UIntArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is ULongArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is UShortArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        is UByteArray -> checkElements(path, value.take(limits.maxEntries + 1), depth, limits, budget)
        else -> "$path has type ${value::class.java.name}, which is not loggable; " +
            "register a ValueAdapter or wrap it in LogSafeValue"
    }

    private fun checkElements(
        path: String,
        values: List<Any?>,
        depth: Int,
        limits: EventLimits,
        budget: Budget,
    ): String? {
        if (depth >= MAX_DEPTH) return "$path is nested deeper than $MAX_DEPTH levels"
        if (values.size > limits.maxEntries) return "$path has ${values.size} entries; the limit is ${limits.maxEntries}"
        for ((index, nested) in values.withIndex()) {
            checkValue("$path[$index]", nested, depth + 1, limits, budget)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------
    // TRUNCATE
    // ------------------------------------------------------------------

    private fun truncate(
        limits: EventLimits,
        actor: Actor?,
        entity: String?,
        entityId: String?,
        source: String?,
        category: String?,
        metadata: Map<String, Any?>,
        changes: List<FieldChange>,
        failure: LogFailure?,
    ): Outcome {
        val sanitizer = Sanitizer(limits)
        val limitedEntity = entity?.let { sanitizer.string(it) }
        val limitedEntityId = entityId?.let { sanitizer.string(it) }
        val limitedSource = source?.let { sanitizer.string(it) }
        val limitedCategory = category?.let { sanitizer.string(it) }

        val limitedActor = actor?.let { sanitizer.actor(it) }
        val limitedMetadata = sanitizer.metadata(metadata)
        val limitedChanges = sanitizer.changes(changes)
        val limitedFailure = failure?.let { sanitizer.failure(it) }

        val truncated = sanitizer.truncated
        val finalMetadata = if (truncated) sanitizer.markTruncated(limitedMetadata) else limitedMetadata
        sanitizer.finish()
        if (sanitizer.used > limits.maxEventBytes) {
            return rejected("event payload exceeds the limit of ${limits.maxEventBytes} bytes even after truncation")
        }
        return Outcome(
            accepted = true,
            truncated = truncated,
            violation = null,
            actor = limitedActor,
            entity = limitedEntity,
            entityId = limitedEntityId,
            source = limitedSource,
            category = limitedCategory,
            metadata = finalMetadata,
            changes = limitedChanges,
            failure = limitedFailure,
        )
    }

    private class Sanitizer(private val limits: EventLimits) {
        // The truncation flag is reserved up front so it can always be added
        // without exceeding the budget; clean events get the bytes back.
        private val flagReservation: Int = EventLimits.TRUNCATED_KEY.length + BOOLEAN_BYTES

        var used: Int = flagReservation
            private set
        var truncated: Boolean = false
            private set

        fun finish() {
            if (!truncated) used -= flagReservation
        }

        fun string(value: String): String {
            if (value.length <= limits.maxStringLength && tryCharge(value.length)) return value
            truncated = true
            val room = min(limits.maxStringLength, limits.maxEventBytes - used)
            if (room <= 0) return ""
            if (room <= EventLimits.TRUNCATION_MARKER.length) {
                used += room
                return EventLimits.TRUNCATION_MARKER.take(room)
            }
            val limited = value.take(room - EventLimits.TRUNCATION_MARKER.length) + EventLimits.TRUNCATION_MARKER
            used += limited.length
            return limited
        }

        fun actor(value: Actor): Actor? {
            if (value.attributes.size > limits.maxActorAttributes) truncated = true
            val attributes = LinkedHashMap<String, String>(min(value.attributes.size, limits.maxActorAttributes))
            for ((key, attribute) in value.attributes) {
                if (attributes.size >= limits.maxActorAttributes) break
                val limitedKey = string(key)
                val limitedValue = string(attribute)
                if (used >= limits.maxEventBytes) break
                attributes[limitedKey] = limitedValue
            }
            val id = value.id?.let { string(it) }
            val name = value.name?.let { string(it) }
            return value.copyWith(id = id, name = name, attributes = attributes)
        }

        fun metadata(value: Map<String, Any?>): Map<String, Any?> {
            if (value.size > limits.maxEntries) truncated = true
            val result = LinkedHashMap<String, Any?>(min(value.size, limits.maxEntries))
            addEntries(result, value.entries.iterator())
            return result
        }

        fun changes(value: List<FieldChange>): List<FieldChange> {
            if (value.size > limits.maxEntries) truncated = true
            val result = ArrayList<FieldChange>(min(value.size, limits.maxEntries))
            for (change in value) {
                if (result.size >= limits.maxEntries) break
                val field = string(change.field)
                result += FieldChange(field, value(change.before, 0), value(change.after, 0))
                if (used >= limits.maxEventBytes) {
                    truncated = true
                    break
                }
            }
            return result
        }

        fun failure(value: LogFailure): LogFailure {
            if (value.stackSummary.size > limits.maxFailureFrames) truncated = true
            val frames = ArrayList<String>(min(value.stackSummary.size, limits.maxFailureFrames))
            for (frame in value.stackSummary) {
                if (frames.size >= limits.maxFailureFrames) break
                frames += string(frame)
            }
            return LogFailure(
                errorType = string(value.errorType),
                message = value.message?.let { string(it) },
                rootCauseType = value.rootCauseType?.let { string(it) },
                stackSummary = frames,
                cancelled = value.cancelled,
            )
        }

        fun markTruncated(metadata: Map<String, Any?>): Map<String, Any?> {
            val keep = (limits.maxEntries - 1).coerceAtLeast(0)
            val result = LinkedHashMap<String, Any?>(min(metadata.size, keep) + 1)
            for ((key, value) in metadata) {
                if (result.size >= keep) break
                result[key] = value
            }
            result[EventLimits.TRUNCATED_KEY] = true
            return result
        }

        private fun addEntries(result: MutableMap<String, Any?>, entries: Iterator<Map.Entry<String, Any?>>) {
            var count = 0
            while (entries.hasNext()) {
                if (count >= limits.maxEntries || used >= limits.maxEventBytes) {
                    if (entries.hasNext()) truncated = true
                    break
                }
                val entry = entries.next()
                val key = string(entry.key)
                result[key] = value(entry.value, 0)
                count++
            }
        }

        private fun value(value: Any?, depth: Int): Any? = when (value) {
            null -> {
                tryCharge(1)
                null
            }
            is LogSafeValue -> {
                tryCharge(SCALAR_BYTES)
                value
            }
            is String -> string(value)
            is Char -> {
                tryCharge(CHAR_BYTES)
                value
            }
            is Boolean -> {
                tryCharge(BOOLEAN_BYTES)
                value
            }
            is Byte, is Short, is Int, is Long, is Float, is Double,
            is UByte, is UShort, is UInt, is ULong,
            is java.util.UUID, is java.time.Instant, is java.time.Duration,
            is java.math.BigDecimal, is java.math.BigInteger,
            -> {
                tryCharge(SCALAR_BYTES)
                value
            }
            is Enum<*> -> {
                string(value.name)
                value
            }
            is Map<*, *> -> {
                if (depth >= MAX_DEPTH) {
                    truncated = true
                    string(EventLimits.TRUNCATION_MARKER)
                } else {
                    if (value.size > limits.maxEntries) truncated = true
                    val result = LinkedHashMap<String, Any?>(min(value.size, limits.maxEntries))
                    var count = 0
                    for ((key, nested) in value) {
                        if (count >= limits.maxEntries || used >= limits.maxEventBytes) {
                            truncated = true
                            break
                        }
                        val limitedKey = string(key?.toString() ?: "null")
                        result[limitedKey] = value(nested, depth + 1)
                        count++
                    }
                    result
                }
            }
            is Iterable<*> -> elements(value.take(limits.maxEntries + 1), depth)
            is Array<*> -> elements(value.take(limits.maxEntries + 1), depth)
            is IntArray -> primitives(value.size) { value[it] }
            is LongArray -> primitives(value.size) { value[it] }
            is ShortArray -> primitives(value.size) { value[it] }
            is ByteArray -> primitives(value.size) { value[it] }
            is FloatArray -> primitives(value.size) { value[it] }
            is DoubleArray -> primitives(value.size) { value[it] }
            is BooleanArray -> primitives(value.size) { value[it] }
            is CharArray -> primitives(value.size) { value[it].toString() }
            is UIntArray -> primitives(value.size) { value[it] }
            is ULongArray -> primitives(value.size) { value[it] }
            is UShortArray -> primitives(value.size) { value[it] }
            is UByteArray -> primitives(value.size) { value[it] }
            else -> {
                truncated = true
                val rendered = try {
                    value.toString()
                } catch (error: Throwable) {
                    "<unrenderable ${value.javaClass.name}>"
                }
                string(rendered)
            }
        }

        private fun elements(values: List<Any?>, depth: Int): List<Any?> {
            if (depth >= MAX_DEPTH) {
                truncated = true
                return listOf(string(EventLimits.TRUNCATION_MARKER))
            }
            // Callers pass at most maxEntries + 1 elements, so an oversized
            // collection is detectable without materializing it.
            if (values.size > limits.maxEntries) truncated = true
            val kept = min(values.size, limits.maxEntries)
            val result = ArrayList<Any?>(kept)
            for (index in 0 until kept) {
                if (used >= limits.maxEventBytes) {
                    truncated = true
                    break
                }
                result += value(values[index], depth + 1)
            }
            return result
        }

        private fun primitives(size: Int, element: (Int) -> Any): List<Any?> {
            if (size > limits.maxEntries) truncated = true
            val kept = min(size, limits.maxEntries)
            val result = ArrayList<Any?>(kept)
            for (index in 0 until kept) {
                if (!tryCharge(SCALAR_BYTES)) {
                    truncated = true
                    break
                }
                result += element(index)
            }
            return result
        }

        private fun tryCharge(bytes: Int): Boolean {
            if (used + bytes > limits.maxEventBytes) return false
            used += bytes
            return true
        }
    }

    private class Budget(private val limit: Int) {
        private var used: Int = 0

        val overflowed: Boolean
            get() = used > limit

        fun add(bytes: Int) {
            if (!overflowed) used += bytes
        }
    }

    private const val MAX_DEPTH = 4
    private const val SCALAR_BYTES = 8
    private const val BOOLEAN_BYTES = 1
    private const val CHAR_BYTES = 2
}
