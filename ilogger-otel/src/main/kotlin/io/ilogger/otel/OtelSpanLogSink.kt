package io.ilogger.otel

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.Outcome
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.concurrent.TimeUnit

/**
 * Exports each event as an instantaneous OpenTelemetry span, parented to the
 * trace the event came from.
 *
 * This is the optional "events as traces" direction: the console/file/Room
 * sinks remain the system of record, while an observability backend gets a
 * correlated span per event without the application writing manual span code.
 * Without a registered SDK the tracer is a no-op and the sink costs almost
 * nothing.
 *
 * Parentage comes from the metadata produced by [OtelTraceEnricher]
 * (`trace.traceId`, `trace.spanId`, `trace.sampled`); events without trace
 * metadata become root spans. Because all values are carried by the event
 * itself, the export stays correct under asynchronous and buffered dispatch,
 * where the producer's span is no longer current.
 *
 * ## Attributes
 *
 * Event fields are written as `ilogger.*` attributes (action, level, outcome,
 * actor, entity, ids, category, source, failure type/message). With
 * [includeMetadata], event metadata becomes `ilogger.meta.<key>` string
 * attributes, capped by [maxMetadataEntries]. Trace metadata keys are not
 * duplicated as attributes.
 *
 * @property tracer span factory; defaults to the global provider
 * @property spanNamePrefix prefix for the span name (`ilogger.<action>`)
 * @property metadataPrefix the enricher's key prefix, used to find the parent
 * @property includeMetadata export metadata as span attributes
 * @property maxMetadataEntries upper bound for exported metadata attributes
 * @property maxAttributeLength truncation bound for string attribute values
 */
public class OtelSpanLogSink(
    private val tracer: Tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME),
    private val spanNamePrefix: String = "ilogger.",
    private val metadataPrefix: String = "trace.",
    private val includeMetadata: Boolean = true,
    private val maxMetadataEntries: Int = 64,
    private val maxAttributeLength: Int = 1_024,
) : LogSink {

    init {
        require(maxMetadataEntries >= 0) { "maxMetadataEntries must be >= 0, got $maxMetadataEntries" }
        require(maxAttributeLength > 0) { "maxAttributeLength must be > 0, got $maxAttributeLength" }
    }

    override suspend fun write(event: LogEvent) {
        val builder = tracer.spanBuilder(spanNamePrefix + event.action)
            .setParent(parentContext(event))
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(event.timestampMillis, TimeUnit.MILLISECONDS)
            .setAllAttributes(attributesFor(event))
        val span = builder.startSpan()
        try {
            if (event.failure != null || event.outcome == Outcome.FAILURE) {
                span.setStatus(StatusCode.ERROR, describe(event))
            } else {
                span.setStatus(StatusCode.OK)
            }
        } finally {
            // An event marks a point in time; a zero-duration span keeps the
            // original timestamp instead of the (much later) delivery time.
            span.end(event.timestampMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun parentContext(event: LogEvent): Context {
        val traceId = event.metadata["${metadataPrefix}traceId"]?.toString() ?: return Context.root()
        val spanId = event.metadata["${metadataPrefix}spanId"]?.toString() ?: return Context.root()
        if (!W3cTraceParent.isTraceId(traceId) || !W3cTraceParent.isSpanId(spanId)) {
            return Context.root()
        }
        val sampled = event.metadata["${metadataPrefix}sampled"] as? Boolean ?: true
        val spanContext = SpanContext.createFromRemoteParent(
            traceId,
            spanId,
            if (sampled) TraceFlags.getSampled() else TraceFlags.getDefault(),
            TraceState.getDefault(),
        )
        return Context.root().with(Span.wrap(spanContext))
    }

    private fun attributesFor(event: LogEvent): Attributes {
        val attributes = Attributes.builder()
        attributes.put(AttributeKey.stringKey("ilogger.event.id"), event.id)
        attributes.put(AttributeKey.stringKey("ilogger.action"), event.schemaId)
        attributes.put(AttributeKey.stringKey("ilogger.level"), event.level.name)
        attributes.put(AttributeKey.stringKey("ilogger.outcome"), event.outcome.name)
        event.source?.let { attributes.put(AttributeKey.stringKey("ilogger.source"), truncate(it)) }
        event.category?.let { attributes.put(AttributeKey.stringKey("ilogger.category"), truncate(it)) }
        event.entity?.let { attributes.put(AttributeKey.stringKey("ilogger.entity"), truncate(it)) }
        event.entityId?.let { attributes.put(AttributeKey.stringKey("ilogger.entityId"), truncate(it)) }
        event.correlationId?.let { attributes.put(AttributeKey.stringKey("ilogger.correlationId"), truncate(it)) }
        event.causationId?.let { attributes.put(AttributeKey.stringKey("ilogger.causationId"), truncate(it)) }
        event.actor?.let { actor ->
            attributes.put(AttributeKey.stringKey("ilogger.actor.type"), actor.type.name)
            actor.id?.let { attributes.put(AttributeKey.stringKey("ilogger.actor.id"), truncate(it)) }
            actor.name?.let { attributes.put(AttributeKey.stringKey("ilogger.actor.name"), truncate(it)) }
        }
        event.failure?.let { failure ->
            attributes.put(AttributeKey.stringKey("ilogger.failure.type"), truncate(failure.errorType))
            attributes.put(AttributeKey.booleanKey("ilogger.failure.cancelled"), failure.cancelled)
            failure.rootCauseType?.let {
                attributes.put(AttributeKey.stringKey("ilogger.failure.rootCauseType"), truncate(it))
            }
            failure.message?.let {
                attributes.put(AttributeKey.stringKey("ilogger.failure.message"), truncate(it))
            }
        }
        if (includeMetadata) {
            var exported = 0
            for ((key, value) in event.metadata) {
                if (exported >= maxMetadataEntries) break
                if (key.startsWith(metadataPrefix)) continue
                val text = when (value) {
                    null -> continue
                    is String -> value
                    is Number, is Boolean -> value.toString()
                    else -> value.toString()
                }
                attributes.put(AttributeKey.stringKey("ilogger.meta.$key"), truncate(text))
                exported++
            }
        }
        return attributes.build()
    }

    private fun describe(event: LogEvent): String =
        truncate(event.failure?.message ?: event.failure?.errorType ?: event.outcome.name)

    private fun truncate(value: String): String =
        if (value.length <= maxAttributeLength) value else value.take(maxAttributeLength)

}

private const val INSTRUMENTATION_NAME: String = "io.ilogger"
