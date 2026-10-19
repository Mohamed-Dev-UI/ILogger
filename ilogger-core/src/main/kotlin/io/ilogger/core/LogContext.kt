package io.ilogger.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Ambient logging context carried by coroutines. It is an ordinary coroutine
 * context element, which makes it safe under concurrency: each coroutine sees
 * only the context established for it (or a supertype-less merge of nested
 * contexts) and values can never leak between concurrently running operations.
 *
 * Typical usage:
 *
 * ```kotlin
 * withActor(Actor.user("u123")) {
 *     withCorrelation("req-abc") {
 *         repository.createTask(task)   // audit events inside carry actor + correlation
 *     }
 * }
 * ```
 *
 * ## Nesting rules
 *
 * Nested helper scopes merge predictably (see [plus]): fields explicitly set
 * by the inner scope win; collections accumulate. A nested scope can therefore
 * replace the actor while keeping the correlation id, etc.
 *
 * ## Framework independence
 *
 * This is a plain `kotlin.coroutines.CoroutineContext` element; no framework
 * or `Dispatchers` defaults are involved.
 */
public class LogContext(
    /** Actor to attribute events in this scope (unless overridden per event). */
    public val actor: Actor? = null,

    /** Groups related events (e.g. one HTTP request or use case). */
    public val correlationId: String? = null,

    /** Links an event to the event that caused it (see [ILogger.record] docs). */
    public val causationId: String? = null,

    /**
     * Free additional key-value context (e.g. `tenantId`, `sessionId`,
     * `traceId`). Applications control the vocabulary; values are copied into
     * event metadata under `context.<key>` when selected via source
     * expressions.
     */
    public val attributes: Map<String, String> = emptyMap(),

    /**
     * Event [LogEvent.source] prefixes to suppress. Used to prevent duplicate
     * events when operations are wrapped at several layers (repository + DAO).
     * Prefix matching: a source is suppressed when it matches any entry in
     * this set exactly.
     */
    public val suppressSources: Set<String> = emptySet(),
) : AbstractCoroutineContextElement(LogContext) {

    public companion object Key : CoroutineContext.Key<LogContext>

    /**
     * Merges [other] (the inner scope) into this context (the outer scope):
     * scalar fields set by [other] win, collection fields accumulate.
     */
    public operator fun plus(other: LogContext): LogContext = LogContext(
        actor = other.actor ?: actor,
        correlationId = other.correlationId ?: correlationId,
        causationId = other.causationId ?: causationId,
        attributes = if (other.attributes.isEmpty()) attributes else attributes + other.attributes,
        suppressSources = if (other.suppressSources.isEmpty()) suppressSources else suppressSources + other.suppressSources,
    )

    override fun equals(other: Any?): Boolean =
        other is LogContext && other.actor == actor && other.correlationId == correlationId &&
            other.causationId == causationId && other.attributes == attributes &&
            other.suppressSources == suppressSources

    override fun hashCode(): Int {
        var result = actor?.hashCode() ?: 0
        result = 31 * result + (correlationId?.hashCode() ?: 0)
        result = 31 * result + (causationId?.hashCode() ?: 0)
        result = 31 * result + attributes.hashCode()
        result = 31 * result + suppressSources.hashCode()
        return result
    }

    override fun toString(): String =
        "<LogContext actor=$actor correlation=$correlationId causation=$causationId attributes=$attributes>"
}

/**
 * Entry points for reading and establishing [LogContext].
 */
public object LogContexts {

    /** Non-suspend thread-local bridge used by non-suspend dispatch paths. */
    @PublishedApi
    internal val threadLocal: ThreadLocal<LogContext?> = ThreadLocal<LogContext?>()

    /**
     * The ambient context in a suspend context: the coroutine context element
     * when present, otherwise the thread-local bridge.
     */
    public suspend fun current(): LogContext {
        coroutineContext[LogContext]?.let { return it }
        return threadLocal.get() ?: LogContext()
    }

    /**
     * The ambient context for non-suspend code. Only reads the thread-local
     * bridge, which is populated by [onCurrentThread] — actor/correlation
     * from `withActor` in suspend scopes is invisible here by design, because
     * it is not thread-owned state.
     */
    public fun currentThread(): LogContext = threadLocal.get() ?: LogContext()

    /**
     * Runs [block] with [context] available as the ambient thread-local
     * context. For non-suspend code (scheduled jobs, callbacks).
     */
    public inline fun <R> onCurrentThread(context: LogContext, block: () -> R): R {
        if (context == LogContext()) return block()
        val previous = threadLocal.get()
        threadLocal.set(context)
        try {
            return block()
        } finally {
            threadLocal.set(previous)
        }
    }
}

/** Runs [block] with [actor] attributed to every event emitted within it. */
public suspend fun <R> withActor(actor: Actor, block: suspend CoroutineScope.() -> R): R =
    withContext(LogContexts.current().plus(LogContext(actor = actor)), block)

/** Runs [block] with [correlationId] attached to every event emitted within it. */
public suspend fun <R> withCorrelation(correlationId: String, block: suspend CoroutineScope.() -> R): R =
    withContext(LogContexts.current().plus(LogContext(correlationId = correlationId)), block)

/**
 * Runs [block] with [causationId] (the event id of the causing event)
 * attached to every event emitted within it.
 */
public suspend fun <R> withCausation(causationId: String, block: suspend CoroutineScope.() -> R): R =
    withContext(LogContexts.current().plus(LogContext(causationId = causationId)), block)

/**
 * Runs [block] with an additional (or replaced) ambient [context]. Scalar
 * values present in [context] override the ambient ones; collections merge.
 */
public suspend fun <R> withLogContext(context: LogContext, block: suspend CoroutineScope.() -> R): R =
    withContext(LogContexts.current().plus(context), block)

/** Convenience: adds [attributes] to the ambient attribute set for [block]. */
public suspend fun <R> withContextAttributes(
    vararg attributes: Pair<String, String>,
    block: suspend CoroutineScope.() -> R,
): R = withContext(LogContexts.current().plus(LogContext(attributes = attributes.toMap())), block)
