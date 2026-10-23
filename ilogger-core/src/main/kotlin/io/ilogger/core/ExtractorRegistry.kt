package io.ilogger.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Registry of the extractor instances used by generated wrappers.
 *
 * Generated code resolves each `LogMeta(extractor = ...)` class through
 * [ILogger.extractors]: a factory registered here wins, otherwise the
 * generated no-argument fallback is used. Resolution is lazy, cached and
 * thread-safe — one instance per extractor class per logger, shared by every
 * wrapper that names the class.
 *
 * ```kotlin
 * ILogger.create {
 *     extractor(TaskSummaryExtractor::class) { TaskSummaryExtractor(dependencies.repo) }
 *     sink(sink)
 * }
 * ```
 *
 * Register every extractor **before the logger is used**: once an extractor
 * class has been resolved (or is resolving), later registrations for that
 * class are rejected instead of being silently ignored.
 *
 * An extractor without a no-argument constructor must be registered; naming
 * it with `LogMeta(..., inject = true)` makes generated code require the
 * registration at first use.
 */
public class ExtractorRegistry {

    private val lock = Any()
    private val factories = HashMap<KClass<*>, () -> Any>()
    private val holders = ConcurrentHashMap<KClass<*>, Lazy<Any>>()

    /** True when no factory has been registered (generated fallbacks still apply). */
    public val isEmpty: Boolean
        get() = synchronized(lock) { factories.isEmpty() }

    /**
     * Registers [factory] as the source of the single instance for [type].
     * The factory runs on first use, outside any registry lock.
     *
     * @throws IllegalStateException when [type] was already resolved; register
     *   all extractors during logger construction.
     */
    public fun <T : Any> register(type: KClass<T>, factory: () -> T) {
        synchronized(lock) {
            check(!holders.containsKey(type)) {
                "ILogger: an extractor for ${type.qualifiedName ?: type} was already resolved; " +
                    "register every extractor before the logger is used."
            }
            factories[type] = factory
        }
    }

    /** Class-based overload for Java callers. */
    public fun <T : Any> register(type: Class<T>, factory: () -> T) {
        register(type.kotlin, factory)
    }

    /** Registers an existing [instance]; equivalent to a constant factory. */
    public fun <T : Any> register(type: KClass<T>, instance: T) {
        register(type) { instance }
    }

    /** Class-based overload for Java callers. */
    public fun <T : Any> register(type: Class<T>, instance: T) {
        register(type.kotlin, instance)
    }

    /**
     * Returns the shared instance for [type]: the registered factory's product,
     * or [fallback] constructed once when nothing is registered.
     *
     * Thread-safe: concurrent first calls observe exactly one instance even
     * when the factory itself is not thread-safe.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> get(type: KClass<T>, fallback: () -> T): T =
        holder(type, fallback).value as T

    /**
     * Returns the registered instance for [type], or throws with registration
     * instructions. Used by generated code for `inject = true` extractors,
     * which have no constructible fallback.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> require(type: KClass<T>): T {
        val factory = synchronized(lock) { factories[type] }
        // A holder created by a get()-fallback does not satisfy "registered".
        holders[type]?.let { if (factory != null) return it.value as T }
        if (factory == null) {
            throw IllegalStateException(
                "ILogger: no extractor is registered for ${type.qualifiedName ?: type}, and the extractor " +
                    "is declared with inject = true.\n" +
                    "Fix: ILogger.create { extractor(${type.simpleName ?: "Extractor"}::class) { " +
                    "${type.simpleName ?: "Extractor"}(...) } }.",
            )
        }
        val created = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { factory() }
        return (holders.putIfAbsent(type, created) ?: created).value as T
    }

    private fun holder(type: KClass<*>, fallback: () -> Any): Lazy<Any> =
        holders.computeIfAbsent(type) {
            // Only capture the factory under the registry lock; user code runs
            // later, inside the Lazy, so a factory may resolve other values.
            val factory = synchronized(lock) { factories[type] } ?: fallback
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) { factory() }
        }
}
