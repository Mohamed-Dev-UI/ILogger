package io.ilogger.otel

import io.ilogger.core.LogContext
import io.ilogger.core.withLogContext
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * Bridge between the current OpenTelemetry span and ILogger's ambient
 * [LogContext].
 *
 * The logger stays framework-free: nothing here is needed for plain logging.
 * Use this object when the application already runs OpenTelemetry
 * instrumentation and wants audit events to join its traces.
 *
 * ## Correlation
 *
 * Register [OtelTraceEnricher] on the logger once; every event then carries
 * `trace.traceId` / `trace.spanId` / `trace.traceparent` metadata captured on
 * the producer thread, so the ids survive asynchronous dispatch:
 *
 * ```kotlin
 * val logger = ILogger.create {
 *     sink(ConsoleLogSink())
 *     enricher(OtelTraceEnricher())
 * }
 *
 * tracer.spanBuilder("checkout").startSpan().use { span ->
 *     span.makeCurrent().use {
 *         logger.record(action = "order.placed")   // carries the span's ids
 *     }
 * }
 * ```
 *
 * ## Propagation
 *
 * [withRemoteParent] makes an inbound W3C `traceparent` the parent of spans
 * created inside [block] and publishes the same value as
 * `LogContext.attributes["traceparent"]`. Ktor's `HttpAuditClient` already
 * forwards that attribute, and `ilogger-ktor` / `ilogger-room` do not need any
 * extra dependency: call the helper at the request or job boundary.
 *
 * ```kotlin
 * // Ktor server route
 * OtelTrace.withIncomingTraceParent(call.request.headers["traceparent"]) {
 *     val span = tracer.spanBuilder("handle").startSpan()
 *     span.makeCurrent().use { service.handle() }
 * }
 *
 * // Room / WorkManager job, from a row that stored the header
 * withLogContext(LogContext(attributes = mapOf("traceparent" to stored))) { ... }
 * ```
 */
public object OtelTrace {

    /** [LogContext.attributes] key shared with `ilogger-ktor`'s client plugin. */
    public const val TRACEPARENT_ATTRIBUTE: String = "traceparent"

    /** The valid current span context, or `null` when no span is recording. */
    public fun currentSpanContext(): SpanContext? =
        Span.current().spanContext.takeIf { it.isValid }

    /** The current span as a W3C carrier, or `null` without an active span. */
    public fun currentTraceParent(): W3cTraceParent? =
        currentSpanContext()?.toW3cTraceParent()

    /**
     * The current trace as [LogContext] attributes (empty without a span).
     * Useful to hand a trace to work that starts on another thread.
     */
    public fun currentContextAttributes(): Map<String, String> =
        currentTraceParent()?.toContextAttributes() ?: emptyMap()

    /**
     * Runs [block] with the current trace available as
     * `LogContext.attributes["traceparent"]`, so asynchronous work started
     * inside can carry it. Does not change the current span; it already is
     * current.
     */
    public suspend fun <R> withCurrentTrace(block: suspend CoroutineScope.() -> R): R {
        val attributes = currentContextAttributes()
        if (attributes.isEmpty()) return coroutineScope(block)
        return withLogContext(LogContext(attributes = attributes), block)
    }

    /**
     * Makes [traceParent] the remote parent of every span created inside
     * [block] and publishes it as ambient context. Invalid input is the
     * caller's responsibility; use [W3cTraceParent.parse] or
     * [withIncomingTraceParent] for untrusted headers.
     */
    public suspend fun <R> withRemoteParent(
        traceParent: W3cTraceParent,
        block: suspend CoroutineScope.() -> R,
    ): R {
        val scope = Span.wrap(traceParent.toSpanContext()).makeCurrent()
        try {
            return withLogContext(LogContext(attributes = traceParent.toContextAttributes()), block)
        } finally {
            scope.close()
        }
    }

    /**
     * [withRemoteParent] for an inbound header: `null` or malformed values are
     * ignored (W3C receivers MUST NOT reject a request over a bad
     * `traceparent`) and [block] runs as if no parent was supplied.
     */
    public suspend fun <R> withIncomingTraceParent(
        header: String?,
        block: suspend CoroutineScope.() -> R,
    ): R {
        val parent = W3cTraceParent.parse(header) ?: return coroutineScope(block)
        return withRemoteParent(parent, block)
    }

}

internal fun SpanContext.toW3cTraceParent(): W3cTraceParent =
    W3cTraceParent(traceId, spanId, traceFlags.isSampled)
