package io.ilogger.core

/**
 * Runtime validation policy for event payloads (see the strict-mode section in
 * the configuration reference).
 *
 * Validation runs at event admission, before the [LogEvent] is allocated:
 *
 * - [OFF] — no validation; the default.
 * - [WARN] — invalid events are dropped and reported through
 *   [io.ilogger.core.ILoggerConfig.onDrop] with [DropReason.STRICT_REJECT].
 * - [THROW] — like [WARN], and `record*` additionally throws the
 *   [IllegalArgumentException] describing the violation.
 *
 * THROW is a development mode: instrumentation must never decide whether a
 * business operation succeeds. Use WARN in debug builds and OFF in production.
 */
public enum class StrictMode { OFF, WARN, THROW }

/**
 * Explicit opt-in for a metadata/change value that is not a log-safe primitive.
 *
 * ```kotlin
 * logger.record(
 *     action = "export.finished",
 *     metadata = mapOf("report" to LogSafeValue.of(reportSummary)),
 * )
 * ```
 *
 * The wrapper stays in [LogEvent.metadata] (it is not unwrapped), so sinks and
 * codecs can identify explicitly validated values; [toString] delegates to the
 * wrapped object. Only wrap values whose [toString] is safe and free of
 * secrets: strict mode cannot inspect the object's internals.
 */
public class LogSafeValue private constructor(public val value: Any?) {

    public companion object {
        public fun of(value: Any?): LogSafeValue = LogSafeValue(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Validates an event's payload against the configured strict limits.
 * Returns a human-readable violation or `null` when the payload is acceptable.
 */
internal object StrictValidation {

    private const val MAX_DEPTH = 4
    private const val SCALAR_BYTES = 8
    private const val BOOLEAN_BYTES = 1
    private const val CHAR_BYTES = 2

    fun violation(
        action: String,
        actor: Actor?,
        entity: String?,
        entityId: String?,
        source: String?,
        category: String?,
        metadata: Map<String, Any?>,
        changes: List<FieldChange>,
        maxStringLength: Int,
        maxEntries: Int,
        maxEventBytes: Int,
    ): String? {
        val budget = Budget(maxEventBytes)

        checkString("action", action, maxStringLength, budget)?.let { return it }
        entity?.let { checkString("entity", it, maxStringLength, budget)?.let { error -> return error } }
        entityId?.let { checkString("entityId", it, maxStringLength, budget)?.let { error -> return error } }
        source?.let { checkString("source", it, maxStringLength, budget)?.let { error -> return error } }
        category?.let { checkString("category", it, maxStringLength, budget)?.let { error -> return error } }

        if (actor != null) {
            actor.id?.let { checkString("actor.id", it, maxStringLength, budget)?.let { error -> return error } }
            actor.name?.let { checkString("actor.name", it, maxStringLength, budget)?.let { error -> return error } }
            if (actor.attributes.size > maxEntries) {
                return "actor.attributes has ${actor.attributes.size} entries; the strict limit is $maxEntries"
            }
            for ((key, value) in actor.attributes) {
                checkKey("actor.attributes", key, maxStringLength, budget)?.let { return it }
                checkString("actor.attributes[$key]", value, maxStringLength, budget)?.let { return it }
            }
        }

        if (metadata.size > maxEntries) {
            return "metadata has ${metadata.size} entries; the strict limit is $maxEntries"
        }
        for ((key, value) in metadata) {
            checkKey("metadata", key, maxStringLength, budget)?.let { return it }
            checkValue("metadata[$key]", value, 0, maxStringLength, maxEntries, budget)?.let { return it }
        }

        if (changes.size > maxEntries) {
            return "changes has ${changes.size} entries; the strict limit is $maxEntries"
        }
        for (change in changes) {
            checkKey("changes.field", change.field, maxStringLength, budget)?.let { return it }
            checkValue("changes[${change.field}].before", change.before, 0, maxStringLength, maxEntries, budget)
                ?.let { return it }
            checkValue("changes[${change.field}].after", change.after, 0, maxStringLength, maxEntries, budget)
                ?.let { return it }
        }

        if (budget.overflowed) {
            return "event payload exceeds the strict byte estimate of $maxEventBytes"
        }
        return null
    }

    private fun checkKey(
        path: String,
        key: String,
        maxStringLength: Int,
        budget: Budget,
    ): String? {
        if (key.isBlank()) return "$path key must not be blank"
        return checkString("$path key '$key'", key, maxStringLength, budget)
    }

    private fun checkString(
        path: String,
        value: String,
        maxStringLength: Int,
        budget: Budget,
    ): String? {
        if (value.length > maxStringLength) {
            return "$path is ${value.length} characters; the strict limit is $maxStringLength"
        }
        budget.add(value.length)
        return null
    }

    private fun checkValue(
        path: String,
        value: Any?,
        depth: Int,
        maxStringLength: Int,
        maxEntries: Int,
        budget: Budget,
    ): String? = when (value) {
        null -> null
        is LogSafeValue -> {
            budget.add(SCALAR_BYTES)
            null
        }
        is String -> checkString(path, value, maxStringLength, budget)
        is Char -> {
            budget.add(CHAR_BYTES)
            null
        }
        is Boolean -> {
            budget.add(BOOLEAN_BYTES)
            null
        }
        is Byte, is Short, is Int, is Long, is Float, is Double -> {
            budget.add(SCALAR_BYTES)
            null
        }
        is UByte, is UShort, is UInt, is ULong -> {
            budget.add(SCALAR_BYTES)
            null
        }
        is java.util.UUID, is java.time.Instant, is java.time.Duration -> {
            budget.add(SCALAR_BYTES)
            null
        }
        is java.math.BigDecimal, is java.math.BigInteger -> {
            budget.add(SCALAR_BYTES)
            null
        }
        is Enum<*> -> checkString("$path (enum)", value.name, maxStringLength, budget)
        is Map<*, *> -> {
            if (depth >= MAX_DEPTH) {
                "$path is nested deeper than $MAX_DEPTH levels"
            } else if (value.size > maxEntries) {
                "$path has ${value.size} entries; the strict limit is $maxEntries"
            } else {
                var violation: String? = null
                for ((key, nested) in value) {
                    if (key !is String) {
                        violation = "$path has a non-string key of type ${key?.let { it::class.java.name } ?: "null"}"
                        break
                    }
                    violation = checkKey(path, key, maxStringLength, budget)
                    if (violation != null) break
                    violation = checkValue("$path[$key]", nested, depth + 1, maxStringLength, maxEntries, budget)
                    if (violation != null) break
                }
                violation
            }
        }
        is Iterable<*> -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is Array<*> -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is IntArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is LongArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is ShortArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is ByteArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is FloatArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is DoubleArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is BooleanArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is CharArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is UIntArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is ULongArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is UShortArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        is UByteArray -> checkElements(path, value.toList(), depth, maxStringLength, maxEntries, budget)
        else -> "$path has type ${value::class.java.name}, which is not log-safe; " +
            "wrap it in LogSafeValue to opt in"
    }

    private fun checkElements(
        path: String,
        values: List<Any?>,
        depth: Int,
        maxStringLength: Int,
        maxEntries: Int,
        budget: Budget,
    ): String? {
        if (depth >= MAX_DEPTH) return "$path is nested deeper than $MAX_DEPTH levels"
        if (values.size > maxEntries) return "$path has ${values.size} entries; the strict limit is $maxEntries"
        for ((index, nested) in values.withIndex()) {
            checkValue("$path[$index]", nested, depth + 1, maxStringLength, maxEntries, budget)?.let { return it }
        }
        return null
    }

    private class Budget(private val limit: Int) {
        private var used: Int = 0

        val overflowed: Boolean
            get() = used > limit

        fun add(bytes: Int) {
            if (!overflowed) used += bytes
        }
    }
}
