package io.ilogger.otel

import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState

/**
 * A validated W3C [Trace Context](https://www.w3.org/TR/trace-context/)
 * `traceparent` value.
 *
 * Parsing is strict (lowercase hex, exact field widths, version and all-zero
 * id rejection) so invalid inbound headers are ignored instead of producing
 * spans that no backend can correlate. Version `ff` is invalid; future
 * versions are accepted when they carry at least the four version-00 fields.
 *
 * ```kotlin
 * val parent = W3cTraceParent.parse(call.request.headers["traceparent"]) ?: return@withContext
 * withRemoteParent(parent) {
 *     // events and spans created here belong to the caller's trace
 * }
 * ```
 *
 * @property traceId 32 lowercase hex characters, not all zero
 * @property spanId 16 lowercase hex characters, not all zero
 * @property sampled the low bit of the trace flags
 */
public data class W3cTraceParent(
    public val traceId: String,
    public val spanId: String,
    public val sampled: Boolean = true,
) {

    init {
        require(isTraceId(traceId)) { "traceId must be 32 lowercase hex characters and not all zero" }
        require(isSpanId(spanId)) { "spanId must be 16 lowercase hex characters and not all zero" }
    }

    /** Formats the [traceparent](https://www.w3.org/TR/trace-context/#traceparent-header) header value. */
    public fun format(): String =
        "00-$traceId-$spanId-" + if (sampled) "01" else "00"

    /**
     * The remote parent as an OpenTelemetry [SpanContext]. Flags other than
     * `sampled` are not representable here and are dropped.
     */
    public fun toSpanContext(): SpanContext = SpanContext.createFromRemoteParent(
        traceId,
        spanId,
        if (sampled) TraceFlags.getSampled() else TraceFlags.getDefault(),
        TraceState.getDefault(),
    )

    /** Ambient [io.ilogger.core.LogContext] attribute carrying this value. */
    public fun toContextAttributes(): Map<String, String> =
        mapOf(OtelTrace.TRACEPARENT_ATTRIBUTE to format())

    public companion object {

        /**
         * Parses a `traceparent` header; returns `null` for `null`, blank or
         * malformed input, as W3C requires receivers to ignore invalid values.
         */
        public fun parse(header: String?): W3cTraceParent? {
            if (header == null) return null
            val value = header.trim()
            val parts = value.split('-')
            if (parts.size < 4) return null
            val version = parts[0]
            if (version.length != 2 || !isHex(version) || version == "ff") return null
            // Version 00 is exactly four fields; later versions may append
            // fields that this parser deliberately ignores.
            if (version == "00" && parts.size != 4) return null
            val traceId = parts[1]
            val spanId = parts[2]
            val flags = parts[3]
            if (!isTraceId(traceId) || !isSpanId(spanId)) return null
            if (flags.length != 2 || !isHex(flags)) return null
            return W3cTraceParent(
                traceId = traceId,
                spanId = spanId,
                sampled = flags.toInt(16) and 0x01 != 0,
            )
        }

        internal fun isTraceId(value: String): Boolean =
            value.length == 32 && isHex(value) && value.any { it != '0' }

        internal fun isSpanId(value: String): Boolean =
            value.length == 16 && isHex(value) && value.any { it != '0' }

        internal fun isHex(value: String): Boolean =
            value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
