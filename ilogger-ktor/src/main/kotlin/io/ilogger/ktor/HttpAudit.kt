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
import io.ilogger.core.LogClock
import io.ilogger.core.LogContext
import io.ilogger.core.ILogger
import io.ilogger.core.Outcome
import io.ilogger.core.SystemClock
import io.ilogger.core.withLogContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

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
 * Recording is suspend-based (`ILogger.record`), so buffered/backpressured
 * loggers are used correctly instead of the non-suspend thread-local bridge.
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

private val EmittedKey: AttributeKey<Boolean> = AttributeKey("ilogger.requestEventEmitted")

private val SampledKey: AttributeKey<Boolean> = AttributeKey("ilogger.sampled")

internal fun sampled(rate: Double): Boolean = when {
    rate >= 1.0 -> true
    rate <= 0.0 -> false
    else -> kotlin.random.Random.nextDouble() < rate
}

/**
 * How a failing request is recorded when both `CallFailed` and `ResponseSent`
 * fire for the same call.
 */
public enum class HttpFailureEvents {
    /**
     * One event per request. `CallFailed` owns the failure event (with the
     * structured exception) and `ResponseSent` skips the status event it would
     * otherwise emit. This is the default and avoids duplicate audit rows.
     */
    SINGLE,

    /**
     * Two events for a failure: the `CallFailed` event with the thrown cause,
     * followed by the `ResponseSent` event with the resulting status. Use when
     * downstream consumers need both the handler failure and the wire status.
     */
    BOTH,
}

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

    /** Additional per-call filter; returning `false` skips the request event. */
    public var requestFilter: (ApplicationCall) -> Boolean = { true }

    /**
     * Category attached to `http.request` events. Defaults to `"http"` so
     * category-routed bindings can separate request audits from other events.
     */
    public var category: String? = "http"

    /** Clock used for request durations; swap with a `FixedClock` in tests. */
    public var clock: LogClock = SystemClock

    /**
     * Header carrying the correlation id. Requests lacking it get a generated
     * UUID and a response header echoing the effective id.
     */
    public var correlationHeader: String = "X-Request-Id"

    /**
     * Fraction of requests recorded (`1.0` by default). The decision is made
     * once at call setup and applies to the single/dual failure events as
     * well; correlation ids are still established for unsampled calls.
     */
    public var sampling: Double = 1.0

    /** Failure-event policy when both Ktor hooks fire. See [HttpFailureEvents]. */
    public var failureEvents: HttpFailureEvents = HttpFailureEvents.SINGLE
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
        call.attributes.put(StartKey, config.clock.nowMillis())
        call.attributes.put(SampledKey, sampled(config.sampling))
        if (incoming == null) {
            call.response.headers.append(config.correlationHeader, correlationId)
        }
    }

    on(ResponseSent) { call ->
        val config = pluginConfig
        val path = call.request.path()
        if (config.isExcluded(call)) return@on
        if (call.attributes.getOrNull(SampledKey) == false) return@on
        if (config.failureEvents == HttpFailureEvents.SINGLE &&
            call.attributes.getOrNull(EmittedKey) == true
        ) {
            return@on
        }

        val status = call.response.status() ?: return@on
        val start = call.attributes.getOrNull(StartKey)
        val durationMillis = if (start != null) config.clock.nowMillis() - start else -1L

        call.attributes.put(EmittedKey, true)
        val statusCode = status.value
        val method = call.request.httpMethod.value
        val correlationId = call.attributes.getOrNull(CorrelationKey)
        val actor = config.actorExtractor(call)
        // ResponseSent is a non-suspend engine hook; start the suspend record
        // without blocking the response and run it to completion on the app
        // scope if the logger applies backpressure.
        application.launch(start = CoroutineStart.UNDISPATCHED) {
            config.logger.record(
                action = "http.request",
                level = LogLevel.AUDIT,
                entity = "HttpRequest",
                entityId = correlationId,
                actor = actor,
                outcome = if (statusCode >= 400) Outcome.FAILURE else Outcome.SUCCESS,
                source = "ktor.server",
                category = config.category,
                metadata = mapOf(
                    "method" to method,
                    "path" to path,
                    "status" to statusCode,
                    "durationMillis" to durationMillis,
                ),
            )
        }
    }

    on(CallFailed) { call, cause ->
        val config = pluginConfig
        val path = call.request.path()
        if (config.isExcluded(call)) return@on
        if (call.attributes.getOrNull(SampledKey) == false) return@on
        if (config.failureEvents == HttpFailureEvents.SINGLE &&
            call.attributes.getOrNull(EmittedKey) == true
        ) {
            return@on
        }

        call.attributes.put(EmittedKey, true)
        config.logger.record(
            action = "http.request",
            level = LogLevel.ERROR,
            entity = "HttpRequest",
            entityId = call.attributes.getOrNull(CorrelationKey),
            actor = config.actorExtractor(call),
            outcome = Outcome.FAILURE,
            failure = cause,
            source = "ktor.server",
            category = config.category,
            metadata = mapOf(
                "method" to call.request.httpMethod.value,
                "path" to path,
                "status" to (call.response.status()?.value ?: 500),
            ),
        )
    }
}

private fun HttpLogConfig.isExcluded(call: ApplicationCall): Boolean {
    val path = call.request.path()
    return excludePaths.any { path.startsWith(it) } || !requestFilter(call)
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
