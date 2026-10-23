# 33. Remote HTTP sink (`ilogger-remote`)

Ships events to a collector over HTTP with the failure handling a logging
pipeline needs: batches, gzip, retries with backoff, a durable offline spool,
a circuit breaker and a dead-letter fallback. The transport is an SPI, so the
HTTP client is your choice.

```kotlin
implementation("io.github.mohamed-dev-ui:ilogger-remote:<version>")
```

JVM 17+ only — this module uses `java.net.http` and `java.nio.file`. Android
apps should keep events in Room ([19](19-room-integration.md)) and ship them
with a WorkManager-backed transport in app code; Android does not include
`java.net.http`.

## Minimal production setup

```kotlin
val sink = RemoteLogSink(
    RemoteLogSinkConfig(
        transport = JavaHttpTransport("https://logs.example.com/v1/events"),
        spool = FileRemoteSpool(Path.of("var/log/ilogger-spool.jsonl")),
        headers = { mapOf("Authorization" to "Bearer ${tokens.current()}") },
        deadLetter = FileRemoteDeadLetter(Path.of("var/log/ilogger-dead-letter.jsonl")),
        onError = { error -> metrics.increment("ilogger.remote.error", error) },
    ),
)

ILogger.create {
    sink(sink, category = "http")
    dispatch = DispatchMode.BUFFERED   // keep retries off the hot path
    batchSize = 128
    closeTimeoutMillis = 10_000        // flush drains the spool before close
}
```

Never configure the sink with `DispatchMode.INLINE`: a network timeout would
then sit on the caller's thread.

## Delivery model

1. `writeAll` encodes each event with the configured `EventCodec`
   (`JsonEventCodec` by default) into one JSON line per event.
2. The lines are appended to the spool. If the spool is full the batch is
   dropped and reported through `onError` (`RemoteSpoolFullException`).
3. The sink drains the spool oldest-first: `peek(maxBatchSize)`, send, and only
   after a 2xx remove those lines.
4. Any number of writers is serialized by one mutex, so events are delivered
   in append order. Producers never see a delivery exception — failures go to
   `onError`.

Without a spool, each batch is sent directly. Failed batches then go to the
dead-letter handler immediately, because there is nowhere to retry them later.

### Status handling

| Outcome | Examples | Behavior |
|---|---|---|
| Success | 200–299 | Lines removed from the spool, circuit-breaker failure count reset |
| Retryable | timeouts, DNS/TLS/connection errors, 408, 429, all 5xx | Backoff, then retry up to `maxAttempts`; lines stay spooled when attempts run out |
| Permanent | 400, 401, 403, 404, 413, other 4xx | Lines removed and handed to the dead-letter handler; retrying would not help |

Exponential backoff starts at `retryBaseMillis` and doubles up to
`retryMaxMillis`. A `Retry-After` header (seconds) overrides the computed
wait and is capped at `retryMaxMillis`.

### Idempotency and duplicate suppression

Each request carries `Idempotency-Key: <batchId>`. `batchId` is the first 32
hex characters of the SHA-256 of the batch's encoded lines, so retries,
offline recovery after a restart and re-sends of the same lines all reuse the
same key. A receiver that has already accepted a batch can answer 2xx again
without storing duplicates.

Inside the payload every event keeps its own unique `id` (the engine's
`EventIdGenerator`), so a collector that splits or reorders batches can also
deduplicate per event. The batch key is a content hash, not a secret; do not
use it as an authentication token.

## Configuration reference

`RemoteLogSinkConfig` validates every value at construction:

| Property | Default | Meaning |
|---|---|---|
| `transport` | — (required) | `RemoteTransport` SPI implementation |
| `codec` | `JsonEventCodec` | Encodes an event into one line |
| `spool` | `null` | Durable buffer; strongly recommended |
| `deadLetter` | `null` | Receives batches that can never be delivered |
| `headers` | `{ emptyMap() }` | `suspend` provider resolved before **every** attempt |
| `gzip` | `true` | Compress the body (`Content-Encoding: gzip`) |
| `maxBatchSize` | `128` | Events per request (1–10,000) |
| `maxAttempts` | `3` | Attempts per batch, including the first (1–100) |
| `retryBaseMillis` | `250` | First backoff wait |
| `retryMaxMillis` | `10_000` | Backoff and `Retry-After` cap |
| `circuitBreaker` | 5 failures / 30 s | See below |
| `clock` | `SystemClock` | Cooldown time source (test seam) |
| `onError` | `null` | Delivery/spool failures; exceptions it throws are contained |

The header provider is where rotating OAuth tokens belong: it runs per
attempt, so a retry after a refresh picks up the new token. It must not block
the thread; use a cached token and refresh in the background.

## Offline spool

`FileRemoteSpool` is a JSONL file capped by `maxBytes` (64 MiB by default):

```kotlin
val spool = FileRemoteSpool(Path.of("var/log/ilogger-spool.jsonl"), maxBytes = 256L * 1024 * 1024)
```

- Appends are `O_APPEND`; removals rewrite the file through a temp file plus
  `ATOMIC_MOVE`, so a crash leaves either the old or the new queue.
- The cap is checked before every append. A full spool reports
  `RemoteSpoolFullException` and drops the incoming batch; the already-spooled
  events survive and drain when connectivity returns.
- One process per spool file. The file holds plaintext events: put it on
  restricted storage, or encrypt payloads before they reach the sink.

Implement `RemoteSpool` to back the buffer with SQLite or another store;
`peek` must return events in append order.

## Dead letter

`RemoteDeadLetter` receives the raw JSONL lines plus a `RemoteFailure`
(description and HTTP status when applicable) for every batch that was
rejected permanently or exhausted its retries without a spool.
`FileRemoteDeadLetter` appends them to a file for later inspection and replay:

```kotlin
val deadLetter = FileRemoteDeadLetter(Path.of("var/log/ilogger-dead-letter.jsonl"))
```

Replay is deliberate, not automatic: inspect, fix the payload or credentials,
then feed the lines back to the collector yourself.

## Circuit breaker

During an outage, retrying every write wastes the request budget. After
`failureThreshold` consecutive retryable failures the breaker opens for
`openMillis`: the transport is not called, events keep spooling, and
`onError` reports `RemoteSendException("circuit breaker is open")`. The first
attempt after the cooldown is the probe — success closes the circuit and
resets the count, failure reopens it for another window.

## Transports, TLS and pinning

`RemoteTransport` has one suspend method:

```kotlin
public fun interface RemoteTransport {
    public suspend fun send(batch: RemoteBatch): RemoteResponse
}
```

Implementations throw on network failure (timeout, DNS, TLS, refused
connection) and return `RemoteResponse(status)` for every HTTP status. Do not
retry inside the transport — the sink owns retries and backoff.

### JDK HttpClient

`JavaHttpTransport` is dependency-free and configured with an endpoint,
optional `HttpClient` and per-request timeout:

```kotlin
val client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .sslContext(pinnedSslContext)   // your trust store / pinning setup
    .build()

val transport = JavaHttpTransport(
    endpoint = "https://logs.example.com/v1/events",
    client = client,
    requestTimeout = Duration.ofSeconds(10),
)
```

Certificate pinning is a trust-manager concern: supply an `SSLContext` whose
trust manager accepts only the pinned certificate (leaf or SPKI). The sink
stays transport-agnostic.

### OkHttp

```kotlin
class OkHttpTransport(
    private val client: OkHttpClient,
    private val url: String,
) : RemoteTransport {
    override suspend fun send(batch: RemoteBatch): RemoteResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Idempotency-Key", batch.batchId)
                .header("Content-Type", batch.contentType)
                .apply { batch.contentEncoding?.let { header("Content-Encoding", it) } }
                .apply { batch.headers.forEach { (name, value) -> header(name, value) } }
                .post(batch.body.toRequestBody(batch.contentType.toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                RemoteResponse(
                    status = response.code,
                    retryAfterMillis = response.header("Retry-After")
                        ?.toLongOrNull()?.times(1_000),
                )
            }
        }
}
```

OkHttp adds `CertificatePinner`, connection pooling and HTTP/2 — a good fit
when those are already part of your stack.

### Ktor

```kotlin
class KtorTransport(
    private val client: HttpClient,
    private val url: String,
) : RemoteTransport {
    override suspend fun send(batch: RemoteBatch): RemoteResponse {
        val response = client.post(url) {
            batch.headers.forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.ContentType, batch.contentType)
            header("Idempotency-Key", batch.batchId)
            batch.contentEncoding?.let { header(HttpHeaders.ContentEncoding, it) }
            setBody(batch.body)
        }
        return RemoteResponse(
            status = response.status.value,
            retryAfterMillis = response.headers[HttpHeaders.RetryAfter]
                ?.toLongOrNull()?.times(1_000),
        )
    }
}
```

Ktor's `HttpClient` throws on non-2xx only when `expectSuccess` is enabled;
turn it off so every status reaches the sink as a `RemoteResponse`.

## Shutdown

`flush()` drains the spool and is best-effort: undelivered lines stay on disk
for the next process. `close()` stops accepting writes; the engine already
calls `flush()` before `close()` within `closeTimeoutMillis`. Size that
timeout for one drain pass, not for the whole outage — the spool exists so a
slow collector does not extend shutdown.

## What this sink does not do

- **No encryption.** Payloads are plaintext JSONL unless you add a
  `LogSink`/`EventCodec` layer that encrypts fields before they reach the
  spool. The spool file is plaintext on disk.
- **No delivery guarantee.** Full spool, `maxAttempts` exhaustion without a
  spool, and permanent failures all lose the batch unless a dead letter is
  configured. Check `onError` counters.
- **No multi-process coordination.** One logger per spool file.
- **No retry of dead letters.** Replay is an operator decision.

For tamper-evident local copies before shipping, pair the remote sink with a
`TextFileSink` hash chain ([30](30-integrity.md)); the remote copy is a
second destination, not a replacement for the durable local trail.
