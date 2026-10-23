package io.ilogger.core

/**
 * Supplies additional event metadata at admission time.
 *
 * Enrichers run on the producer's thread, while the ambient [LogContext] and
 * any thread-scoped telemetry (for example the current OpenTelemetry span) are
 * still visible. That is what makes them the right seam for trace/span ids:
 * by the time a sink sees the event, asynchronous dispatch has moved it to a
 * worker thread where no producer span is current.
 *
 * ## Rules
 *
 * - Explicit event metadata always wins: an enricher can never overwrite a
 *   key the caller provided.
 * - Among enrichers, the first one to return a key wins; later registrations
 *   only fill gaps.
 * - Returned values pass through the configured [ValueAdapter]s, limits,
 *   redaction, sampling and strict validation exactly like caller metadata.
 * - A throwing enricher never drops the event. The failure is reported through
 *   [ILoggerBuilder.onDispatchError] and that enricher's map is ignored for
 *   the event.
 *
 * ```kotlin
 * ILogger.create {
 *     enricher { context -> mapOf("tenant" to context.attributes["tenantId"]) }
 * }
 * ```
 */
public fun interface LogEventEnricher {

    /**
     * Returns metadata to merge into the event admitted under [context].
     * Values must be loggable (primitives, strings, [LogSafeValue]s).
     */
    public fun enrich(context: LogContext): Map<String, Any?>
}
