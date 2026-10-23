package io.ilogger.core

/**
 * Converts a log-safe representation of a value for [LogEvent.metadata].
 *
 * Extractors let applications decide how their own domain objects become
 * safe log values — ILogger never serializes whole objects by default.
 *
 * Extractor classes are referenced from [LogMeta (annotation)][io.ilogger.annotations.LogMeta].
 * Generated wrappers resolve them through [ILogger.extractors]: a factory
 * registered with `ILogger.create { extractor(...) }` wins, otherwise the
 * class is instantiated through its public no-argument constructor once per
 * logger. Extractors whose constructor needs application services should be
 * declared with `inject = true` and registered explicitly.
 *
 * Extraction runs synchronously on the caller's path; suspend extractors are
 * intentionally not part of the contract, because suspending inside event
 * construction would delay the business call and complicate START/SUCCESS
 * ordering. Load what you need beforehand and project in-memory state.
 */
public fun interface LogValueExtractor<in T> {
    /** Converts [value] into loggable primitives, or null to omit. */
    public fun extract(value: T): Any?

    /**
     * A view of this extractor that never throws and only emits log-safe
     * values: a throwing or invalid extraction becomes `null` (the entry is
     * omitted). Generated wrappers always call this view, so a buggy extractor
     * cannot break the business call or smuggle an arbitrary object into an
     * event.
     */
    @Suppress("UNCHECKED_CAST")
    public fun validated(): LogValueExtractor<T> =
        if (this is ValidatedExtractor<*>) this as LogValueExtractor<T> else ValidatedExtractor(this)
}

/** Applies [LogSafeValues.isLogSafe] and exception containment to an extractor. */
internal class ValidatedExtractor<T>(
    private val delegate: LogValueExtractor<T>,
) : LogValueExtractor<T> {
    override fun extract(value: T): Any? {
        val output = try {
            delegate.extract(value)
        } catch (_: Throwable) {
            return null
        }
        return if (LogSafeValues.isLogSafe(output)) output else null
    }
}

/**
 * Built-in transforms applied when generated code processes an explicitly
 * selected value marked [io.ilogger.annotations.LogSensitive].
 */
public object LogMasking {
    /** Placeholder used for masked values. */
    public const val MASK: String = "***"

    /**
     * Returns a truncated SHA-256 fingerprint of [value]: deterministic,
     * non-reversible and stable across processes — useful for correlating the
     * same actor across events without exposing the value itself.
     */
    public fun hash(value: Any?): String? {
        if (value == null) return null
        val string = value.toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(string.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }

    /** Number of hex characters kept from the full digest. */
    public const val HASH_LENGTH: Int = 24
}
