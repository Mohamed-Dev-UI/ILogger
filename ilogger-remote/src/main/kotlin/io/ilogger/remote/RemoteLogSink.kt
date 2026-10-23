package io.ilogger.remote

import io.ilogger.core.LogClock
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.SystemClock
import io.ilogger.core.sink.EventCodec
import io.ilogger.serialization.JsonEventCodec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thrown through [RemoteLogSinkConfig.onError] when a batch cannot be sent. */
public class RemoteSendException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Thrown through [RemoteLogSinkConfig.onError] when the spool is full. */
public class RemoteSpoolFullException(
    message: String,
) : IOException(message)

/**
 * Circuit-breaker thresholds for [RemoteLogSink].
 *
 * After [failureThreshold] consecutive retryable failures the sink stops
 * calling the transport for [openMillis]; events keep spooling. The first
 * attempt after the cooldown is the probe: success closes the circuit, failure
 * reopens it for another [openMillis].
 */
public data class RemoteCircuitBreakerConfig(
    public val failureThreshold: Int = 5,
    public val openMillis: Long = 30_000,
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be > 0, got $failureThreshold" }
        require(openMillis > 0) { "openMillis must be > 0, got $openMillis" }
    }
}

/**
 * Configuration for [RemoteLogSink].
 *
 * @property transport sends each batch; see [JavaHttpTransport]
 * @property codec encodes events into JSONL lines (JSON by default)
 * @property spool durable offline buffer — strongly recommended in production
 * @property deadLetter final destination for batches that can never be sent
 * @property headers resolved before **every** attempt, so rotating bearer
 *   tokens are picked up; values are sent as HTTP headers
 * @property gzip compress the request body (`Content-Encoding: gzip`)
 * @property maxBatchSize maximum events per request (1–10,000)
 * @property maxAttempts attempts per batch, including the first (1–100)
 * @property retryBaseMillis first exponential-backoff wait
 * @property retryMaxMillis cap for backoff and `Retry-After`
 * @property circuitBreaker outage protection; see [RemoteCircuitBreakerConfig]
 * @property clock time source for circuit-breaker cooldowns (test seam)
 * @property onError receives delivery and spool failures; never throws into
 *   the engine
 */
public class RemoteLogSinkConfig(
    public val transport: RemoteTransport,
    public val codec: EventCodec = JsonEventCodec,
    public val spool: RemoteSpool? = null,
    public val deadLetter: RemoteDeadLetter? = null,
    public val headers: suspend () -> Map<String, String> = { emptyMap() },
    public val gzip: Boolean = true,
    public val maxBatchSize: Int = 128,
    public val maxAttempts: Int = 3,
    public val retryBaseMillis: Long = 250,
    public val retryMaxMillis: Long = 10_000,
    public val circuitBreaker: RemoteCircuitBreakerConfig = RemoteCircuitBreakerConfig(),
    public val clock: LogClock = SystemClock,
    public val onError: ((Throwable) -> Unit)? = null,
) {
    init {
        require(maxBatchSize in 1..10_000) { "maxBatchSize must be in 1..10_000, got $maxBatchSize" }
        require(maxAttempts in 1..100) { "maxAttempts must be in 1..100, got $maxAttempts" }
        require(retryBaseMillis >= 0) { "retryBaseMillis must be >= 0, got $retryBaseMillis" }
        require(retryMaxMillis >= retryBaseMillis) {
            "retryMaxMillis must be >= retryBaseMillis, got $retryMaxMillis < $retryBaseMillis"
        }
    }
}

/**
 * Ships events to an HTTP endpoint in batched, gzip-compressed JSONL requests.
 *
 * ## Delivery model
 *
 * [writeAll] encodes the batch, appends it to the [RemoteLogSinkConfig.spool]
 * (when configured), and drains the spool oldest-first. Each request carries
 * `Idempotency-Key: <batchId>`; `batchId` is a stable hash of the encoded
 * lines, so retries and post-restart recovery reuse it. Event ids inside the
 * payload are unique, so receivers can deduplicate per event as well.
 *
 * Retryable failures (timeouts, connection errors, 408, 429, 5xx) are retried
 * with exponential backoff and an honored `Retry-After`; when attempts are
 * exhausted the lines stay in the spool and are retried on the next write or
 * [flush]. Permanent failures (other 4xx) are removed from the spool and
 * handed to [RemoteLogSinkConfig.deadLetter]. Without a spool, failed batches
 * go straight to the dead-letter handler, so configure one in production.
 *
 * The circuit breaker stops transport calls during an outage while events keep
 * spooling. All state is guarded by one mutex, so writes are serialized and
 * delivered in append order; producers never see a delivery exception.
 *
 * ## Lifecycle
 *
 * [flush] drains the spool and is best-effort. [close] stops accepting writes;
 * the engine calls [flush] before [close], and any lines still undelivered
 * remain on disk for the next process. Pair the sink with `DispatchMode.ASYNC`
 * or `BUFFERED` so retries happen off the hot path.
 */
public class RemoteLogSink(
    private val config: RemoteLogSinkConfig,
) : LogSink {

    /** Convenience for best-effort delivery without a spool. */
    public constructor(transport: RemoteTransport) : this(RemoteLogSinkConfig(transport))

    private val mutex = Mutex()
    private val breaker = CircuitBreaker(config.circuitBreaker)

    @Volatile
    private var closed: Boolean = false

    override suspend fun write(event: LogEvent): Unit = writeAll(listOf(event))

    override suspend fun writeAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        val lines = events.map { event -> config.codec.encode(event) }

        mutex.withLock {
            if (closed) {
                report(IllegalStateException("RemoteLogSink is closed; ${lines.size} event(s) dropped"))
                return@withLock
            }
            val spool = config.spool
            if (spool == null) {
                sendDirect(lines)
                return@withLock
            }
            val stored = try {
                spool.append(lines)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                report(error)
                sendDirect(lines)
                return@withLock
            }
            if (!stored) {
                report(
                    RemoteSpoolFullException(
                        "remote spool is full; dropped ${lines.size} event(s). " +
                            "Increase maxBytes or reduce outage length.",
                    ),
                )
                return@withLock
            }
            drain(spool)
        }
    }

    override suspend fun flush() {
        mutex.withLock {
            if (closed) return@withLock
            val spool = config.spool ?: return@withLock
            try {
                drain(spool)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                report(error)
            }
        }
    }

    override fun close() {
        closed = true
    }

    private suspend fun sendDirect(lines: List<String>) {
        when (val result = deliver(lines)) {
            Delivery.Delivered -> Unit
            is Delivery.Permanent -> deadLetter(lines, result.failure)
            is Delivery.RetryLater -> deadLetter(lines, result.failure)
        }
    }

    private suspend fun drain(spool: RemoteSpool) {
        while (true) {
            if (closed) return
            val lines = spool.peek(config.maxBatchSize)
            if (lines.isEmpty()) return

            when (val result = deliver(lines)) {
                Delivery.Delivered -> spool.removeFirst(lines.size)
                is Delivery.Permanent -> {
                    spool.removeFirst(lines.size)
                    deadLetter(lines, result.failure)
                }
                is Delivery.RetryLater -> {
                    report(
                        RemoteSendException(
                            "batch of ${lines.size} event(s) remains spooled: ${result.failure.description}",
                        ),
                    )
                    return
                }
            }
        }
    }

    private suspend fun deliver(lines: List<String>): Delivery {
        val prepared = prepare(lines)
        var lastFailure = RemoteFailure("no attempt was made")

        for (attempt in 0 until config.maxAttempts) {
            val now = config.clock.nowMillis()
            if (!breaker.tryAcquire(now)) {
                return Delivery.RetryLater(RemoteFailure("circuit breaker is open"))
            }

            val headers = try {
                config.headers()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastFailure = RemoteFailure("header provider failed: ${error.message}")
                breaker.recordFailure(now)
                if (attempt == config.maxAttempts - 1) break
                delay(backoffMillis(attempt))
                continue
            }

            val batch = RemoteBatch(
                batchId = prepared.batchId,
                lines = lines,
                body = prepared.body,
                contentType = CONTENT_TYPE,
                contentEncoding = prepared.contentEncoding,
                headers = headers,
            )

            val response = try {
                config.transport.send(batch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastFailure = RemoteFailure("transport failed: ${error.message}")
                breaker.recordFailure(now)
                if (attempt == config.maxAttempts - 1) break
                delay(backoffMillis(attempt))
                continue
            }

            when {
                response.isSuccess -> {
                    breaker.recordSuccess()
                    return Delivery.Delivered
                }
                response.isPermanent -> {
                    return Delivery.Permanent(
                        RemoteFailure("HTTP ${response.status}", status = response.status),
                    )
                }
                else -> {
                    lastFailure = RemoteFailure("HTTP ${response.status}", status = response.status)
                    breaker.recordFailure(now)
                    if (attempt == config.maxAttempts - 1) break
                    delay(retryDelayMillis(response, attempt))
                }
            }
        }
        return Delivery.RetryLater(lastFailure)
    }

    private suspend fun deadLetter(lines: List<String>, failure: RemoteFailure) {
        report(
            RemoteSendException(
                "batch of ${lines.size} event(s) was not delivered: ${failure.description}",
            ),
        )
        val deadLetter = config.deadLetter ?: return
        val batch = RemoteBatch(
            batchId = batchId(lines),
            lines = lines,
            body = lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8),
            contentType = CONTENT_TYPE,
            contentEncoding = null,
            headers = emptyMap(),
        )
        try {
            deadLetter.deliver(batch, failure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            report(error)
        }
    }

    private fun prepare(lines: List<String>): Prepared {
        val payload = lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        return if (config.gzip) {
            Prepared(batchId(lines), gzip(payload), "gzip")
        } else {
            Prepared(batchId(lines), payload, null)
        }
    }

    private fun retryDelayMillis(response: RemoteResponse, attempt: Int): Long {
        val retryAfter = response.retryAfterMillis ?: return backoffMillis(attempt)
        return retryAfter.coerceIn(0, config.retryMaxMillis)
    }

    private fun backoffMillis(attempt: Int): Long {
        if (config.retryBaseMillis == 0L) return 0
        var wait = config.retryBaseMillis
        repeat(attempt) {
            wait = (wait * 2).coerceAtMost(config.retryMaxMillis)
        }
        return wait
    }

    private fun report(error: Throwable) {
        try {
            config.onError?.invoke(error)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A broken error handler must not break delivery.
        }
    }

    private class Prepared(
        val batchId: String,
        val body: ByteArray,
        val contentEncoding: String?,
    )

    private sealed interface Delivery {
        data object Delivered : Delivery
        data class Permanent(val failure: RemoteFailure) : Delivery
        data class RetryLater(val failure: RemoteFailure) : Delivery
    }

    private class CircuitBreaker(private val config: RemoteCircuitBreakerConfig) {
        private var consecutiveFailures = 0
        private var openUntilMillis: Long? = null

        fun tryAcquire(now: Long): Boolean {
            val openUntil = openUntilMillis ?: return true
            if (now < openUntil) return false
            openUntilMillis = null
            return true
        }

        fun recordSuccess() {
            consecutiveFailures = 0
            openUntilMillis = null
        }

        fun recordFailure(now: Long) {
            consecutiveFailures++
            if (consecutiveFailures >= config.failureThreshold) {
                openUntilMillis = now + config.openMillis
            }
        }
    }

    private companion object {
        const val CONTENT_TYPE: String = "application/x-ndjson; charset=utf-8"

        fun gzip(payload: ByteArray): ByteArray = ByteArrayOutputStream(payload.size).use { bytes ->
            GZIPOutputStream(bytes).use { gzip -> gzip.write(payload) }
            bytes.toByteArray()
        }

        fun batchId(lines: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte ->
                ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
            }.take(32)
        }
    }
}
