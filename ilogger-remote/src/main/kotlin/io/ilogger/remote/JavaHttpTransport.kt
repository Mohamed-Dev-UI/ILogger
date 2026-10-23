package io.ilogger.remote

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [RemoteTransport] backed by the JDK's [HttpClient] — no third-party runtime
 * dependency, for JVM 17+ servers and desktop processes. Android does not ship
 * `java.net.http`; use an OkHttp or Ktor [RemoteTransport] there.
 *
 * ```kotlin
 * val sink = RemoteLogSink(
 *     RemoteLogSinkConfig(
 *         transport = JavaHttpTransport("https://logs.example.com/v1/events"),
 *         spool = FileRemoteSpool(Path.of("var/log/ilogger-spool.jsonl")),
 *         headers = { mapOf("Authorization" to "Bearer ${tokens.current()}") },
 *     ),
 * )
 * ```
 *
 * **TLS and pinning.** Pass a preconfigured [client] to control the
 * `SSLContext`, cipher suites and timeout. Certificate pinning is done by
 * supplying a trust manager that only accepts the pinned certificate (or by
 * writing an OkHttp transport with `CertificatePinner`); the sink itself is
 * transport-agnostic.
 *
 * The transport sends one `POST` per batch with the batch's content type,
 * optional `Content-Encoding`, `Idempotency-Key` and any headers resolved by
 * the sink's header provider. Network failures are thrown as
 * [RemoteTransportException]; HTTP statuses are returned as [RemoteResponse].
 */
public class JavaHttpTransport(
    endpoint: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(15),
) : RemoteTransport {

    private val uri: URI = URI.create(endpoint)

    init {
        require(uri.scheme == "http" || uri.scheme == "https") {
            "endpoint must be an absolute http(s) URL, got $endpoint"
        }
        require(uri.host != null) { "endpoint must include a host, got $endpoint" }
        require(!requestTimeout.isZero && !requestTimeout.isNegative) {
            "requestTimeout must be positive, got $requestTimeout"
        }
    }

    override suspend fun send(batch: RemoteBatch): RemoteResponse = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofByteArray(batch.body))

        batch.headers.forEach { (name, value) -> builder.setHeader(name, value) }
        builder.setHeader("Content-Type", batch.contentType)
        builder.setHeader("Idempotency-Key", batch.batchId)
        batch.contentEncoding?.let { builder.setHeader("Content-Encoding", it) }

        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RemoteTransportException("POST $uri was interrupted", error)
        } catch (error: Throwable) {
            throw RemoteTransportException("POST $uri failed: ${error.message}", error)
        }

        RemoteResponse(
            status = response.statusCode(),
            retryAfterMillis = retryAfterMillis(response),
            body = response.body(),
        )
    }

    private fun retryAfterMillis(response: HttpResponse<String>): Long? {
        val value = response.headers().firstValue("Retry-After").orElse(null)?.trim() ?: return null
        val seconds = value.toLongOrNull() ?: return null
        if (seconds < 0) return null
        return seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000
    }

    private companion object {
        const val MAX_RETRY_AFTER_SECONDS: Long = 3_600
    }
}
