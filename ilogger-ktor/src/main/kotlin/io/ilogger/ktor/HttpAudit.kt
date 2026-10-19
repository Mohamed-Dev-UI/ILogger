package io.ilogger.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.LogContext
import io.ilogger.core.ILogger
import io.ilogger.core.Outcome
import io.ilogger.core.withLogContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

/**
 * Ktor server integration: request/response audit with request-scoped
 * ambient context.
 *
 * ## What it records
 *
 * One `http.request` event per call via [HttpAudit]:
 *
 * - `metadata.method` / `metadata.path` / `metadata.status` / `metadata.durationMillis`
 * - `entityId` = correlation id (from [HttpLogConfig.correlationHeader] or generated)
 * - 4xx and 5xx responses are [Outcome.FAILURE]; other statuses are [Outcome.SUCCESS]
 * - exceptions surface through `on(CallFailed)` with the structured failure
 *   payload attached — telemetry never swallows anything
 *
 * ## Ambient context in handlers
 *
 * Use [withAuditContext] inside route handlers so downstream audit events
 * (e.g. `.logged(...)` DAO wrappers produced by ilogger-ksp) carry the same
 * actor and correlation id. This mirrors the core `withActor` /
 * `withCorrelation` nesting rules: values set inside win, collections merge.
 */

/** Attribute key storing the correlation id resolved at call setup. */
public val CorrelationKey: AttributeKey<String> = AttributeKey("ilogger.correlationId")

private val StartKey: AttributeKey<Long> = AttributeKey("ilogger.startMillis")

/**
 * Configuration for [HttpAudit].
 */
public class HttpLogConfig {

    /** Logger receiving `http.request` events. Defaults to [ILogger.default]. */
    public var logger: ILogger = ILogger.default

    /**
     * Actor extraction per call. Default maps the `X-User-Id` header to
     * [Actor.user] — headers are unauthenticated input, treat accordingly.
     */
    public var actorExtractor: (ApplicationCall) -> Actor? = { call ->
        call.request.headers["X-User-Id"]?.let { Actor.user(it) }
    }

    /** Paths (prefix match, no query) never recorded: health checks, probes. */
    public var excludePaths: Set<String> = setOf("/health", "/ready", "/metrics")

    /**
     * Header carrying the correlation id. Requests lacking it get a generated
     * UUID and a response header echoing the effective id.
     */
    public var correlationHeader: String = "X-Request-Id"
}

/**
 * The request/response audit plugin.
 *
 * ```kotlin
 * install(HttpAudit) {
 *     logger = auditLogger
 *     actorExtractor = { call -> principal<MyUser>(call)?.toActor() }
 * }
 * ```
 */
public val HttpAudit: ApplicationPlugin<HttpLogConfig> = createApplicationPlugin(
    name = "HttpAudit",
    createConfiguration = { HttpLogConfig() },
) {
    on(CallSetup) { call ->
        val config = pluginConfig
        val incoming = call.request.headers[config.correlationHeader]
        val correlationId = incoming ?: java.util.UUID.randomUUID().toString()
        call.attributes.put(CorrelationKey, correlationId)
        call.attributes.put(StartKey, System.currentTimeMillis())
        if (incoming == null) {
            call.response.headers.append(config.correlationHeader, correlationId)
        }
    }

    on(ResponseSent) { call ->
        val config = pluginConfig
        val path = call.request.path()
        if (config.excludePaths.any { path.startsWith(it) }) return@on

        val status = call.response.status() ?: return@on
        val start = call.attributes.getOrNull(StartKey)
        val durationMillis = if (start != null) System.currentTimeMillis() - start else -1L

        config.logger.recordNow(
            action = "http.request",
            level = LogLevel.AUDIT,
            entity = "HttpRequest",
            entityId = call.attributes.getOrNull(CorrelationKey),
            actor = config.actorExtractor(call),
            outcome = if (status.value >= 400) Outcome.FAILURE else Outcome.SUCCESS,
            source = "ktor.server",
            metadata = mapOf(
                "method" to call.request.httpMethod.value,
                "path" to path,
                "status" to status.value,
                "durationMillis" to durationMillis,
            ),
        )
    }

    on(CallFailed) { call, cause ->
        val config = pluginConfig
        val path = call.request.path()
        if (config.excludePaths.any { path.startsWith(it) }) return@on

        config.logger.recordNow(
            action = "http.request",
            level = LogLevel.ERROR,
            entity = "HttpRequest",
            entityId = call.attributes.getOrNull(CorrelationKey),
            actor = config.actorExtractor(call),
            outcome = Outcome.FAILURE,
            failure = cause,
            source = "ktor.server",
            metadata = mapOf(
                "method" to call.request.httpMethod.value,
                "path" to path,
            ),
        )
    }
}

/**
 * Runs [block] with this request's audit context as ambient context: the
 * correlation id established by [HttpAudit] (or a fresh UUID when the plugin
 * is not installed), [actor] and free-form [tags].
 *
 * Events emitted by instrumented operations inside [block] inherit this
 * context; nested `withActor`/`withCorrelation` scopes merge predictably.
 */
public suspend fun <R> ApplicationCall.withAuditContext(
    actor: Actor? = null,
    vararg tags: Pair<String, String>,
    block: suspend CoroutineScope.() -> R,
): R {
    val correlationId = attributes.getOrNull(CorrelationKey)
    return withLogContext(
        LogContext(actor = actor, correlationId = correlationId, attributes = tags.toMap()),
        block,
    )
}
