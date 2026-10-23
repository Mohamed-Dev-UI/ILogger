package io.ilogger.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Type-safe configuration for the ILogger Gradle plugin.
 *
 * ```kotlin
 * ilogger {
 *     enabled.set(true)
 *     strict.set(true)
 *     domainPackages.add("com.acme")
 * }
 * ```
 */
public abstract class ILoggerExtension {

    /**
     * Compile-time kill switch. `false` makes the processor a no-op: nothing is
     * generated, so instrumented call sites (`.logged(...)`) must not be
     * compiled in that build. Defaults to `true`.
     */
    public abstract val enabled: Property<Boolean>

    /**
     * Turns ILogger's compile-time warnings into errors (for example an action
     * missing from `@LogActions` or a reused action+version with a changed
     * shape). Defaults to `false`.
     */
    public abstract val strict: Property<Boolean>

    /**
     * Package prefixes eligible for entity inference (e.g. `com.acme`,
     * `com.acme.shared`). Empty keeps the built-in blacklist behavior.
     */
    public abstract val domainPackages: SetProperty<String>

    /**
     * Optional extra copy of the generated action manifest
     * (`META-INF/ilogger/schema.json`) for VCS/CI diffs.
     */
    public abstract val schemaOutput: RegularFileProperty

    /** Logs one build-output line per generated file. Defaults to `false`. */
    public abstract val verbose: Property<Boolean>

    /**
     * Android/JVM variant names (or exact KSP task names) whose processing is
     * disabled, e.g. `add("release")`. Only ILogger's processor is disabled;
     * other processors in the same KSP run are unaffected.
     */
    public abstract val disabledVariants: SetProperty<String>

    /**
     * Diagnostic codes suppressed module-wide, e.g. `add("ILG062")`. Per-symbol
     * `@Suppress("ILGxxx")` works without this. Defaults to empty.
     */
    public abstract val suppress: SetProperty<String>

    /**
     * Warn when an AUDIT operation has no explicit `@Log(actor = ...)` and
     * therefore depends on ambient attribution. Defaults to `false`.
     */
    public abstract val requireActor: Property<Boolean>

    /**
     * Categories that have a sink binding, e.g. `add("audit")`. When non-empty,
     * `@Log` categories outside the set warn (ILG021). Defaults to empty
     * (rule disabled).
     */
    public abstract val sinkCategories: SetProperty<String>

    init {
        enabled.convention(true)
        strict.convention(false)
        verbose.convention(false)
        domainPackages.convention(emptySet())
        disabledVariants.convention(emptySet())
        suppress.convention(emptySet())
        requireActor.convention(false)
        sinkCategories.convention(emptySet())
    }
}
