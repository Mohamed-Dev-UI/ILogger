package io.ilogger.core

import kotlin.reflect.KClass

/**
 * Converts an application value into a log-safe value for [LogEvent.metadata]
 * and [FieldChange] before/after values.
 *
 * Two registration paths exist:
 *
 * - **Runtime registry** ([ILoggerBuilder.valueAdapter]) — applies to manual
 *   `record(...)`/`tryRecord(...)` calls at event materialization, keyed by
 *   the value's exact runtime class.
 * - **Annotation-named** (`LogMeta(adapter = ...)` in ilogger-annotations) —
 *   generated wrappers instantiate the class once and call `render` at the
 *   call site.
 *
 * A well-behaved adapter is pure, fast and returns only log-safe values
 * (numbers, booleans, strings, chars, enums, UUID/Instant/Duration,
 * BigDecimal, one-level containers of those, or null).
 */
public fun interface ValueAdapter<in T> {
    /** Converts [value] into a log-safe value, or null to omit the content. */
    public fun render(value: T): Any?
}

/**
 * Registry of [ValueAdapter]s consulted when an event is materialized.
 *
 * Lookup is by the value's **exact runtime class** — no inheritance walk —
 * so behavior is deterministic and the hot path is a single map hit. An
 * adapter that throws degrades the value to null: logging must never break
 * the application, and strict validation still sees the rendered result.
 */
public class ValueAdapterRegistry {

    private val byType = HashMap<Class<*>, ValueAdapter<Any?>>()

    /** True when no adapter is registered (fast path: values pass through untouched). */
    public val isEmpty: Boolean get() = byType.isEmpty()

    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> register(type: Class<T>, adapter: ValueAdapter<T>) {
        byType[type] = adapter as ValueAdapter<Any?>
    }

    public fun <T : Any> register(type: KClass<T>, adapter: ValueAdapter<T>) {
        register(type.java, adapter)
    }

    /**
     * Renders [value] with the adapter registered for its exact runtime
     * class, or returns it unchanged when none applies. A throwing adapter
     * renders null.
     */
    public fun render(value: Any?): Any? {
        if (value == null) return null
        val adapter = byType[value.javaClass] ?: return value
        return try {
            adapter.render(value)
        } catch (_: Throwable) {
            null
        }
    }
}
