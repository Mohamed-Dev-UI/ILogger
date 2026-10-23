package io.ilogger.annotations

/**
 * What to do when metadata explicitly references a value that is marked
 * [io.ilogger.annotations.LogSensitive].
 *
 * This is chosen at the *selection* site (inside [Log] metadata), not at the
 * *marking* site, so a library can mark sensitive fields and each application
 * decides whether and how they may ever appear in logs.
 */
public enum class SensitiveHandling {
    /**
     * Default: referencing a [io.ilogger.annotations.LogSensitive] value
     * without an explicit policy is a compile-time error. Explicit is
     * required; nothing sensitive leaks by omission.
     */
    DENY,

    /**
     * Omit the entry entirely: no key is generated, no converter runs and no
     * allocation happens for it. On `LogMeta(..., sensitive = OMIT)` the
     * entry never appears; as a container-wide `@LogConfig(sensitive = OMIT)`
     * policy it drops every policy-less selection that touches a sensitive
     * value (and any actor override doing so). Entity ids and changes never
     * accept OMIT — they refuse sensitive sources outright.
     */
    OMIT,

    /** Replace the value with a fixed mask (`"***"`). */
    MASK,

    /** Replace the value with a truncated SHA-256 fingerprint (stable, non-reversible). */
    HASH,
}
