package io.ilogger.core

/**
 * Converts a log-safe representation of a value for [LogEvent.metadata].
 *
 * Extractors let applications decide how their own domain objects become
 * safe log values — ILogger never serializes whole objects by default.
 *
 * Extractor classes are referenced from [LogMeta (annotation)][io.ilogger.annotations.LogMeta];
 * they must provide a public no-argument constructor.
 */
public fun interface LogValueExtractor<in T> {
    /** Converts [value] into loggable primitives, or null to omit. */
    public fun extract(value: T): Any?
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
