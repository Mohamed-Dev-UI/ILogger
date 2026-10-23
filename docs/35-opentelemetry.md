# 35. OpenTelemetry: trace correlation and metrics

`ilogger-otel` joins audit events to distributed traces and publishes engine
health through any OpenTelemetry-compatible metric registry. It is an opt-in
artifact: `ilogger-core` has no OpenTelemetry dependency, and nothing in this
guide is required to use the logger.

## What the module ships

| Component | Direction | Purpose |
|---|---|---|
| `OtelTraceEnricher` | events → traces | Copies `traceId`/`spanId`/`traceparent` into event metadata at admission |
| `OtelTrace` + `W3cTraceParent` | propagation | Parses/formats W3C Trace Context, makes inbound parents current, publishes them as ambient `LogContext` |
| `OtelSpanLogSink` | events → spans | Optional: exports one instantaneous span per event to an OTel backend |
| `OtelLoggerMetrics` | engine → metrics | Observable gauges/counters for accepted, delivered, dropped, queue depth, in-flight |
| `OtelSinkMetrics` | sinks → metrics | Decorator adding write latency, volume, errors and retry counts per sink |

## Install

```kotlin
dependencies {
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.43.0"))
    implementation("io.opentelemetry:opentelemetry-api")
    // Only for the "events as spans" export or the metrics providers below:
    implementation("io.opentelemetry:opentelemetry-sdk")

    implementation("io.github.mohamed-dev-ui:ilogger-otel:2.0.0")
}
```

`ilogger-otel` compiles against `opentelemetry-api` as **compile-only**, the
same contract as the SLF4J bridges ([34](34-logging-bridges.md)): the module
never pins an OpenTelemetry version for the application. Add the API (usually
through the BOM) yourself. Without a registered SDK every OTel API call is a
no-op, so development builds pay almost nothing; the module still requires the
API classes at runtime.

**🏆 Recommended:** one `OpenTelemetrySdk` in the application, passed to every
component explicitly. Avoid `GlobalOpenTelemetry` in tests so span/metric state
cannot leak between cases.

## Trace correlation (the core feature)

Register the enricher once:

```kotlin
val logger = ILogger.create {
    sink(ConsoleLogSink())
    enricher(OtelTraceEnricher())
}
```

Every event — audit and diagnostic — is then stamped **at admission, on the
producer thread**, before value adapters, limits, redaction, sampling and
dispatch:

| Metadata key | Value |
|---|---|
| `trace.traceId` | 32 lowercase hex characters |
| `trace.spanId` | 16 lowercase hex characters |
| `trace.sampled` | `Boolean`, the W3C sampled flag |
| `trace.remote` | `true` when the span context came from a remote parent |
| `trace.traceparent` | full W3C header value |

```kotlin
tracer.spanBuilder("checkout").startSpan().use { span ->
    span.makeCurrent().use {
        logger.record(action = "order.placed", entityId = order.id)
        // metadata carries trace.traceId / trace.spanId of "checkout"
    }
}
```

Why the enricher exists at all: sinks run later, on dispatch worker threads,
where the producer's span is no longer current. Reading `Span.current()` inside
a sink would stamp the wrong trace (or none). Enrichment runs while the trace is
still current, and the ids then travel with the immutable event through
buffering, batching, retries and the remote spool.

Rules that keep this predictable:

- Caller-supplied metadata wins; the enricher only fills missing keys.
- The first registered enricher wins on conflicts.
- A throwing enricher never drops the event; the failure goes to
  `ILoggerBuilder.onDispatchError`.
- Enriched values pass through value adapters, limits and redaction exactly
  like caller metadata. A redaction rule that matches a trace key applies.

## Inbound and outbound propagation

`ilogger-ktor`'s client plugin already forwards
`LogContext.attributes["traceparent"]` (and can generate one), see
[20](20-ktor-integration.md). On the receiving side, make the inbound header
the parent of the request's spans:

```kotlin
// Ktor server route
post("/orders") {
    OtelTrace.withIncomingTraceParent(call.request.headers["traceparent"]) {
        val span = tracer.spanBuilder("orders.create").startSpan()
        span.makeCurrent().use { orders.create(call) }
    }
}
```

- Invalid or missing headers are **ignored**, never rejected: W3C receivers
  must not fail a request because of a bad `traceparent`.
- `withRemoteParent(W3cTraceParent, block)` is the explicit variant when the
  caller already parsed/validated the value.
- Both publish the header as `LogContext.attributes["traceparent"]`, so events
  recorded deeper in the call stack carry it — including events recorded on a
  different thread through `withCurrentTrace`.

Room and background work: `LogContext` is a coroutine context element, so it
follows `withContext(Dispatchers.IO)` into DAO calls. For work that starts
later (WorkManager, a queue), persist the header with the job and restore it:

```kotlin
// enqueue
val traceparent = OtelTrace.currentTraceParent()?.format()
job.inputData = workDataOf("traceparent" to traceparent)

// worker
OtelTrace.withIncomingTraceParent(inputData.getString("traceparent")) {
    repository.syncPending()   // events join the original trace
}
```

Without an OTel SDK the same attribute still works: the enricher falls back to
parsing `traceparent` from the ambient `LogContext`, so ILogger-only
applications can correlate without running spans at all
(`w3cTraceParent.parse(...)` + `withLogContext(...)`).

## Exporting events as spans (optional)

`sink(OtelSpanLogSink(tracer))` writes one short `INTERNAL` span per event,
parented to the trace recorded in the event metadata:

```kotlin
val logger = ILogger.create {
    sink(ConsoleLogSink())                     // system of record
    sink(OtelSpanLogSink(sdk.getTracer("app"))) // observability backend
    enricher(OtelTraceEnricher())
}
```

| Span field | Source |
|---|---|
| name | `ilogger.<action>` (configurable prefix) |
| parent | `trace.traceId` / `trace.spanId` / `trace.sampled` from metadata; root when absent |
| `ilogger.*` attributes | event id, action (+version), level, outcome, actor, entity, ids, category, source, failure type/message/cancelled |
| `ilogger.meta.<key>` | event metadata (`includeMetadata = true`, capped by `maxMetadataEntries`) |
| status | `ERROR` for `Outcome.FAILURE` or a failure payload, otherwise `OK` |

The span is instantaneous: start and end use the event timestamp, so a
delivered-later event still appears at the time it happened. Attribute values
are truncated to `maxAttributeLength` (default 1024).

This export is best-effort by design. It is another sink binding: a slow or
broken exporter is subject to the same error modes and isolation as any sink,
and the console/file/Room sinks remain the audit record of record. Drop this
sink and correlation still works.

## Engine metrics

```kotlin
val meter = sdk.get("io.ilogger")
val metrics = OtelLoggerMetrics(logger, meter)
// ... on shutdown
metrics.close()
logger.close()
```

| Instrument | Type | Labels | Meaning |
|---|---|---|---|
| `ilogger.events.accepted` | counter (delta) | — | admissions since the previous collection |
| `ilogger.events.delivered` | counter (delta) | — | deliveries since the previous collection |
| `ilogger.events.dropped` | counter (delta) | `reason` (`DropReason`) | losses, one series per reason |
| `ilogger.events.truncated` | counter (delta) | — | events rewritten by limits |
| `ilogger.queue.depth` | gauge | — | queued events across bindings |
| `ilogger.events.in_flight` | gauge | — | events being written right now |
| `ilogger.binding.pending` | gauge | `binding.index`, `binding.sink`, `binding.category` | per-binding queue depth |
| `ilogger.binding.in_flight` | gauge | same | per-binding in-flight events |

The instruments are **observable**: their callbacks read `ILogger.stats()` only
when a `MeterProvider` collects. There is no polling thread and no cost while
nothing collects. Counters report deltas between collections, so `rate()` and
`increase()` behave as expected in Prometheus and OTLP.

`OtelLoggerMetrics` owns its registrations — `close()` unregisters them. It
does not close the logger or the provider. Micrometer users can scrape these
instruments through Micrometer's OpenTelemetry bridge; no Micrometer dependency
is shipped here.

Alerting starting points:

- `increase(ilogger.events.dropped[reason=OVERFLOW_NEWEST]) > 0` — producers
  outrunning sinks (see [16](16-dispatch-backpressure-error-modes.md)).
- `ilogger.queue.depth` sustained near `bufferSize` — chronic backlog.
- `increase(ilogger.events.dropped[reason=SINK_FAILURE]) > 0` — a failing sink.
- `ilogger.events.delivered` flat while `accepted` rises — stuck worker.

## Sink metrics (latency, errors, retries)

```kotlin
val file = OtelSinkMetrics.wrap(
    sink = TextFileSink(file = logFile),
    meter = meter,
    sinkName = "audit-file",
)
val logger = ILogger.create { sink(file) }
```

| Instrument | Type | Labels |
|---|---|---|
| `ilogger.sink.write.duration` | histogram (ms) | `sink`, `outcome` (`success`/`error`/`cancelled`) |
| `ilogger.sink.events` | counter | `sink` — events written successfully |
| `ilogger.sink.errors` | counter | `sink`, `error.type` — failed attempts |
| `ilogger.sink.retries` | counter | `sink` — events re-attempted after a failure |

Retries are **observed**, not configured here: the engine's `ErrorMode.RETRY`
calls the sink again with the same event ids, and the decorator counts events
whose previous attempt failed (bounded 128-id memory). A `writeAll` batch counts
each event. Cancellation is recorded as an outcome but not as an error.

Wrap with `OtelSinkMetrics.wrap(...)` rather than the constructor if the
delegate implements `DirectLogSink` (e.g. `ConsoleLogSink`, the Android
Logcat sink): `wrap` preserves the synchronous inline diagnostic path. The
plain constructor accepts any `LogSink` and always uses the coroutine path.

The decorator rethrows everything the sink threw, so error modes, retries and
`onDispatchError` behave exactly as without it.

## Cost and cardinality

- Correlation adds five short metadata entries per event when a trace is
  active; with no span and no `traceparent` attribute the enricher returns an
  empty map.
- Trace ids have fixed cardinality per trace — safe as metric labels, but do
  not add them to metric labels from event metadata.
- `binding.*` gauges emit one series per sink binding, not per event.
- The span sink is the only component that talks to an exporter; keep it off
  the critical path by treating it as an auxiliary sink, exactly like a remote
  collector ([33](33-remote-sink.md)).
- Metadata exported as span attributes has already passed redaction and limits
  at admission. Never put secrets in trace or span attributes.

## Failure behavior

| Situation | Behavior |
|---|---|
| No SDK registered | OTel API no-ops; correlation falls back to `LogContext`; span sink writes nothing |
| Invalid inbound `traceparent` | Ignored; the request proceeds without a remote parent |
| Enricher throws | Reported via `onDispatchError`; the event is recorded without that enricher's keys |
| Span exporter throws | Ordinary sink failure: error mode, retries and counters apply |
| `OtelLoggerMetrics.close()` | Unregisters instruments; later `collect` shows no ILogger series |
| Logger closed before metrics | Gauges read zero; no error |
