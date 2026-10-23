package io.ilogger.otel

import io.ilogger.core.LogContext
import io.ilogger.core.LogEventEnricher

/**
 * Copies the current trace identity into every event's metadata at admission.
 *
 * Resolution order:
 * 1. the OpenTelemetry span current on the producer thread;
 * 2. a W3C `traceparent` stored in [LogContext.attributes] (for example by
 *    [OtelTrace.withCurrentTrace] or `ilogger-ktor`'s client plugin).
 *
 * Because enrichment runs before asynchronous dispatch, ids stay attached even
 * when the event is written later on a worker thread. Caller-supplied metadata
 * wins on conflicts.
 *
 * | Metadata key | Value |
 * |---|---|
 * | `trace.traceId` | 32 lowercase hex characters |
 * | `trace.spanId` | 16 lowercase hex characters |
 * | `trace.sampled` | `Boolean` trace-flags sampled bit |
 * | `trace.remote` | `true` when the span came from a remote parent |
 * | `trace.traceparent` | the full W3C header value |
 *
 * @property metadataPrefix prefix for every emitted key; must not be blank.
 */
public class OtelTraceEnricher(
    public val metadataPrefix: String = "trace.",
) : LogEventEnricher {

    init {
        require(metadataPrefix.isNotBlank()) { "metadataPrefix must not be blank" }
    }

    override fun enrich(context: LogContext): Map<String, Any?> {
        val spanContext = OtelTrace.currentSpanContext()
        val parent = if (spanContext != null) {
            spanContext.toW3cTraceParent()
        } else {
            W3cTraceParent.parse(context.attributes[OtelTrace.TRACEPARENT_ATTRIBUTE])
        } ?: return emptyMap()

        return mapOf(
            "${metadataPrefix}traceId" to parent.traceId,
            "${metadataPrefix}spanId" to parent.spanId,
            "${metadataPrefix}sampled" to parent.sampled,
            "${metadataPrefix}remote" to (spanContext?.isRemote ?: true),
            "${metadataPrefix}traceparent" to parent.format(),
        )
    }
}
