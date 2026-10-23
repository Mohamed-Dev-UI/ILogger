package io.ilogger.remote

import java.io.IOException

/**
 * One HTTP payload produced by [RemoteLogSink].
 *
 * [lines] are the encoded events (one per line, JSONL by default), [body] is
 * the exact byte payload to send (gzip-compressed when [contentEncoding] is
 * `"gzip"`), and [batchId] is a stable content hash. Retries and spool
 * recovery reuse the same [batchId] in the `Idempotency-Key` header, so a
 * receiver can drop duplicates; each event also carries its own unique `id`
 * inside the payload for per-event deduplication.
 *
 * Implement [RemoteTransport] to plug in Ktor, OkHttp, or any other HTTP
 * client; transport implementations only translate this value into a request.
 */
public class RemoteBatch(
    /** Stable idempotency key derived from [lines]. */
    public val batchId: String,
    /** Encoded events, one per line, in send order. */
    public val lines: List<String>,
    /** Exact bytes to transmit (possibly gzip-compressed). */
    public val body: ByteArray,
    /** Content type of [body], e.g. `application/x-ndjson; charset=utf-8`. */
    public val contentType: String,
    /** `"gzip"` when [body] is compressed, otherwise `null`. */
    public val contentEncoding: String?,
    /** Additional request headers resolved for this attempt. */
    public val headers: Map<String, String>,
)

/**
 * HTTP outcome returned by a [RemoteTransport].
 *
 * @property status HTTP status code
 * @property retryAfterMillis parsed `Retry-After` delay in milliseconds, when
 *   the server supplied a seconds value
 * @property body response body, if the transport captured it (diagnostics)
 */
public class RemoteResponse(
    public val status: Int,
    public val retryAfterMillis: Long? = null,
    public val body: String? = null,
) {
    /** 2xx. */
    public val isSuccess: Boolean
        get() = status in 200..299

    /** 4xx that retrying cannot fix (all except 408 and 429). */
    public val isPermanent: Boolean
        get() = status in 400..499 && status != 408 && status != 429

    /** Whether another attempt can plausibly succeed. */
    public val isRetryable: Boolean
        get() = !isSuccess && !isPermanent
}

/**
 * Transport SPI for [RemoteLogSink].
 *
 * Implementations throw on network failures (timeouts, DNS, refused
 * connections) and return [RemoteResponse] for every HTTP status. The sink
 * retries thrown failures and retryable statuses, so transports should not
 * retry on their own.
 *
 * ```kotlin
 * // OkHttp adapter sketch
 * class OkHttpTransport(private val client: OkHttpClient, private val url: String) : RemoteTransport {
 *     override suspend fun send(batch: RemoteBatch): RemoteResponse = withContext(Dispatchers.IO) {
 *         val request = Request.Builder()
 *             .url(url)
 *             .header("Idempotency-Key", batch.batchId)
 *             .header("Content-Type", batch.contentType)
 *             .apply { batch.contentEncoding?.let { header("Content-Encoding", it) } }
 *             .apply { batch.headers.forEach { (name, value) -> header(name, value) } }
 *             .post(batch.body.toRequestBody(batch.contentType.toMediaType()))
 *             .build()
 *         client.newCall(request).execute().use { response ->
 *             RemoteResponse(response.code, response.header("Retry-After")?.toLongOrNull()?.times(1_000))
 *         }
 *     }
 * }
 * ```
 */
public fun interface RemoteTransport {
    /** Sends one batch; throws on network failure. */
    public suspend fun send(batch: RemoteBatch): RemoteResponse
}

/** A transport-level failure (timeout, DNS, TLS, refused connection). */
public class RemoteTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
