package io.ilogger.core

/**
 * Structural check for the log-safe value model, shared by extractor output
 * validation and strict mode.
 *
 * Accepted: `null`, strings, chars, booleans, numbers (including unsigned),
 * enums, `UUID`/`Instant`/`Duration`, `BigDecimal`/`BigInteger`,
 * [LogSafeValue], and — nested up to four levels — maps with string keys,
 * [Iterable]s and arrays. Everything else is not log-safe; wrap an explicit
 * `toString`-safe value in [LogSafeValue] to opt in.
 */
public object LogSafeValues {

    private const val MAX_DEPTH = 4

    /** True when [value] (and everything nested in it) is log-safe. */
    public fun isLogSafe(value: Any?): Boolean = check(value, 0)

    private fun check(value: Any?, depth: Int): Boolean = when (value) {
        null -> true
        is LogSafeValue -> true
        is String, is Char, is Boolean -> true
        is Byte, is Short, is Int, is Long, is Float, is Double -> true
        is UByte, is UShort, is UInt, is ULong -> true
        is java.util.UUID, is java.time.Instant, is java.time.Duration -> true
        is java.math.BigDecimal, is java.math.BigInteger -> true
        is Enum<*> -> true
        is Map<*, *> ->
            depth < MAX_DEPTH && value.all { (key, nested) -> key is String && check(nested, depth + 1) }
        is Iterable<*> -> depth < MAX_DEPTH && value.all { check(it, depth + 1) }
        is Array<*> -> depth < MAX_DEPTH && value.all { check(it, depth + 1) }
        is IntArray, is LongArray, is ShortArray, is ByteArray,
        is FloatArray, is DoubleArray, is BooleanArray, is CharArray,
        is UIntArray, is ULongArray, is UShortArray, is UByteArray,
        -> true
        else -> false
    }
}
