package io.ilogger.ktor

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.ILogger
import io.ilogger.core.LogClock
import io.ilogger.core.LogContexts
import io.ilogger.core.Outcome
import io.ilogger.core.SystemClock
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.util.AttributeKey
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ktor client integration: outbound request/response audit plus correlation
 * and W3C `traceparent` propagation.
 *
 * One `http.client.request` event is recorded per call, whether the call
 * completes, returns an error status or fails before a response arrives. The
 * event carries the effective correlation id (request header, ambient
 * [io.ilogger.core.LogContext], or nothing) as `entityId`, the actor from the
 * config or ambient context, method/path/host/status/duration metadata and —
 * for exceptions — the structured failure with `metadata.transport = true`.
 *
 * ```kotlin
 * val client = HttpClient(CIO) {
 *     install(HttpAuditClient) {
 *         logger = auditLogger
 *     }
 * }
 *
 * withCorrelation("checkout-42") {
 *     client.get("https://inventory.example/stock")   // sends X-Request-Id: checkout-42
 * }
 * ```
 *
 * Header injection never overwrites a header already present on the request.
 */
public class HttpAuditClientConfig {

    /** Logger receiving `http.client.request` events. Defaults to [ILogger.default]. */
    public var logger: ILogger = ILogger.default

    /** Category for outbound events; defaults to `"http"` like the server plugin. */
    public var category: String? = "http"

    /** Level used for successful calls (including 4xx/5xx HTTP responses). */
    public var level: LogLevel = LogLevel.AUDIT

    /** Level used when the call fails before a response exists (transport error). */
    public var failureLevel: LogLevel = LogLevel.ERROR

    /** Actor recorded on every outbound event; ambient actor is the fallback. */
    public var actor: Actor? = null

    /** Header carrying the correlation id; set from the ambient context when absent. */
    public var correlationHeader: String = "X-Request-Id"

    /** Header carrying a W3C `traceparent`; ambient attributes are forwarded when present. */
    public var traceparentHeader: String = "traceparent"

    /**
     * Mint a `traceparent` for requests without one (ambient attribute or
     * existing header). Off by default: a trace id that no tracer owns is
     * misleading unless the application explicitly wants edge-originated traces.
     */
    public var generateTraceparent: Boolean = false

    /**
     * Fraction of outbound calls recorded (`1.0` by default). Sampling only
     * suppresses the event: correlation/trace headers are still propagated and
     * the call is unaffected.
     */
    public var sampling: Double = 1.0

    /** Clock used for durations; swap with a `FixedClock` in tests. */
    public var clock: LogClock = SystemClock

    /** Per-request filter; returning `false` records nothing and injects no headers. */
    public var requestFilter: (HttpRequestBuilder) -> Boolean = { true }
}

private val ClientCorrelationKey: AttributeKey<String> = AttributeKey("ilogger.client.correlationId")

private val ClientActorKey: AttributeKey<Actor> = AttributeKey("ilogger.client.actor")

private val ClientStartKey: AttributeKey<Long> = AttributeKey("ilogger.client.startMillis")

private val ClientSampledKey: AttributeKey<Boolean> = AttributeKey("ilogger.client.sampled")

/**
 * The outbound audit plugin. Install it once per [io.ktor.client.HttpClient]:
 *
 * ```kotlin
 * install(HttpAuditClient) { logger = auditLogger }
 * ```
 */
public val HttpAuditClient: ClientPlugin<HttpAuditClientConfig> = createClientPlugin(
    name = "HttpAuditClient",
    createConfiguration = { HttpAuditClientConfig() },
) {
    val config = pluginConfig

    onRequest { request, _ ->
        if (!config.requestFilter(request)) return@onRequest

        val ambient = LogContexts.current()
        val existingCorrelation = request.headers[config.correlationHeader]
        val correlationId = existingCorrelation ?: ambient.correlationId
        if (existingCorrelation == null && correlationId != null) {
            request.headers.append(config.correlationHeader, correlationId)
        }
        if (correlationId != null) {
            request.attributes.put(ClientCorrelationKey, correlationId)
        }

        val actor = config.actor ?: ambient.actor
        if (actor != null) {
            request.attributes.put(ClientActorKey, actor)
        }

        if (request.headers[config.traceparentHeader] == null) {
            when {
                ambient.attributes["traceparent"] != null ->
                    request.headers.append(config.traceparentHeader, ambient.attributes.getValue("traceparent"))

                config.generateTraceparent ->
                    request.headers.append(config.traceparentHeader, newTraceparent())
            }
        }

        request.attributes.put(ClientStartKey, config.clock.nowMillis())
        request.attributes.put(ClientSampledKey, sampled(config.sampling))
    }

    on(Send) { request ->
        if (!config.requestFilter(request)) {
            proceed(request)
        } else {
            val start = request.attributes.getOrNull(ClientStartKey) ?: config.clock.nowMillis()
            val sampled = request.attributes.getOrNull(ClientSampledKey) != false
            val call = try {
                proceed(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (sampled) recordCall(config, request, start, response = null, error = error)
                throw error
            }
            if (sampled) recordCall(config, request, start, call.response, error = null)
            call
        }
    }
}

private suspend fun recordCall(
    config: HttpAuditClientConfig,
    request: HttpRequestBuilder,
    startMillis: Long,
    response: HttpResponse?,
    error: Throwable?,
) {
    val status = response?.status?.value
    val failed = error != null || (status != null && status >= 400)
    val metadata = buildMap {
        put("method", request.method.value)
        put("host", request.url.host)
        put("path", request.url.build().encodedPath)
        if (status != null) put("status", status)
        put("durationMillis", (config.clock.nowMillis() - startMillis).coerceAtLeast(0))
        if (error != null) put("transport", true)
    }
    config.logger.record(
        action = "http.client.request",
        level = if (error != null) config.failureLevel else config.level,
        entity = "HttpRequest",
        entityId = request.attributes.getOrNull(ClientCorrelationKey),
        actor = request.attributes.getOrNull(ClientActorKey),
        outcome = if (failed) Outcome.FAILURE else Outcome.SUCCESS,
        failure = error,
        source = "ktor.client",
        category = config.category,
        metadata = metadata,
    )
}

private fun newTraceparent(): String {
    val traceId = UUID.randomUUID().toString().replace("-", "")
    val spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    return "00-$traceId-$spanId-01"
}
