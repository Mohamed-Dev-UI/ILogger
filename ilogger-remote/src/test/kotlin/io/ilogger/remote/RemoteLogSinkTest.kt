package io.ilogger.remote

import io.ilogger.annotations.LogLevel
import io.ilogger.core.FixedClock
import io.ilogger.core.LogEvent
import io.ilogger.serialization.JsonEventCodec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun event(id: String): LogEvent = LogEvent(
    id = id,
    timestampMillis = 1_700_000_000_000,
    level = LogLevel.AUDIT,
    action = "remote.test",
)

private fun RecordedRequest.gunzipped(): String =
    GZIPInputStream(body.inputStream()).bufferedReader().use { it.readText() }

class RemoteLogSinkTest {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sink(
        spool: RemoteSpool? = null,
        deadLetter: RemoteDeadLetter? = null,
        maxAttempts: Int = 3,
        gzip: Boolean = true,
        headers: suspend () -> Map<String, String> = { emptyMap() },
        circuitBreaker: RemoteCircuitBreakerConfig = RemoteCircuitBreakerConfig(),
        clock: io.ilogger.core.LogClock = io.ilogger.core.SystemClock,
        onError: ((Throwable) -> Unit)? = null,
        transport: RemoteTransport? = null,
        requestTimeoutMillis: Long = 5_000,
    ): RemoteLogSink = RemoteLogSink(
        RemoteLogSinkConfig(
            transport = transport ?: JavaHttpTransport(
                endpoint = server.url("/logs").toString(),
                requestTimeout = Duration.ofMillis(requestTimeoutMillis),
            ),
            spool = spool,
            deadLetter = deadLetter,
            headers = headers,
            gzip = gzip,
            maxAttempts = maxAttempts,
            retryBaseMillis = 1,
            retryMaxMillis = 10,
            circuitBreaker = circuitBreaker,
            clock = clock,
            onError = onError,
        ),
    )

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))

    @Test
    fun `success sends gzip JSONL with auth and a stable idempotency key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val sink = sink(headers = { mapOf("Authorization" to "Bearer token") })

        sink.writeAll(listOf(event("e1"), event("e2")))

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/logs", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-ndjson"))
        assertEquals(32, request.getHeader("Idempotency-Key")!!.length)

        val lines = request.gunzipped().trim().lines()
        assertEquals(listOf("e1", "e2"), lines.map { JsonEventCodec.decode(it).id })
        sink.close()
    }

    @Test
    fun `gzip can be disabled and headers are refreshed per attempt`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200))
        val attempt = AtomicInteger()
        val sink = sink(
            maxAttempts = 2,
            gzip = false,
            headers = {
                mapOf("Authorization" to "Bearer ${attempt.incrementAndGet()}")
            },
        )

        sink.writeAll(listOf(event("e1")))

        val first = takeRequest()
        val second = takeRequest()
        assertEquals("Bearer 1", first.getHeader("Authorization"))
        assertEquals("Bearer 2", second.getHeader("Authorization"))
        assertNull(second.getHeader("Content-Encoding"))
        assertTrue(second.body.readUtf8().contains("\"id\":\"e1\""))
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        sink.close()
    }

    @Test
    fun `429 is retried and the retry reuses the idempotency key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))
        val sink = sink()

        sink.writeAll(listOf(event("e1")))

        val first = takeRequest()
        val second = takeRequest()
        assertEquals(2, server.requestCount)
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        assertEquals(first.gunzipped(), second.gunzipped())
        sink.close()
    }

    @Test
    fun `exhausted retries keep the events spooled for the next process`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val spool = FileRemoteSpool(folder.newFile("spool.jsonl").toPath())
        val sink = sink(spool = spool, maxAttempts = 1)

        sink.writeAll(listOf(event("e1")))

        assertEquals(1, server.requestCount)
        assertEquals(1, spool.peek(10).size)
        val failedBatchKey = takeRequest().getHeader("Idempotency-Key")
        sink.close()

        val recoveryServer = MockWebServer()
        recoveryServer.start()
        try {
            recoveryServer.enqueue(MockResponse().setResponseCode(200))
            val recovered = RemoteLogSink(
                RemoteLogSinkConfig(
                    transport = JavaHttpTransport(recoveryServer.url("/logs").toString()),
                    spool = spool,
                    maxAttempts = 1,
                    retryBaseMillis = 1,
                ),
            )
            recovered.flush()

            val recoveryRequest = checkNotNull(recoveryServer.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(failedBatchKey, recoveryRequest.getHeader("Idempotency-Key"))
            assertEquals("e1", JsonEventCodec.decode(recoveryRequest.gunzipped().trim()).id)
            assertTrue(spool.peek(10).isEmpty())
            recovered.close()
        } finally {
            recoveryServer.shutdown()
        }
    }

    @Test
    fun `timeouts are reported and spooled`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val spool = FileRemoteSpool(folder.newFile("timeout.jsonl").toPath())
        val errors = ArrayList<Throwable>()
        val sink = sink(spool = spool, maxAttempts = 1, onError = { errors += it }, requestTimeoutMillis = 50)

        sink.writeAll(listOf(event("e1")))

        assertEquals(1, spool.peek(10).size)
        assertTrue(errors.any { it is RemoteSendException })
        sink.close()
    }

    @Test
    fun `permanent 4xx is removed from the spool and dead-lettered`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))
        val spool = FileRemoteSpool(folder.newFile("perm-spool.jsonl").toPath())
        val deadLetterFile = folder.newFile("dead.jsonl").toPath()
        val errors = ArrayList<Throwable>()
        val sink = sink(
            spool = spool,
            deadLetter = FileRemoteDeadLetter(deadLetterFile),
            onError = { errors += it },
        )

        sink.writeAll(listOf(event("e1")))

        assertEquals(1, server.requestCount)
        assertTrue(spool.peek(10).isEmpty())
        assertEquals(listOf("e1"), Files.readAllLines(deadLetterFile).map { JsonEventCodec.decode(it).id })
        assertTrue(errors.any { it is RemoteSendException && it.message!!.contains("400") })
        sink.close()
    }

    @Test
    fun `without a spool failed batches go to the dead-letter fallback`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val deadLetterFile = folder.newFile("direct-dead.jsonl").toPath()
        val sink = sink(
            deadLetter = FileRemoteDeadLetter(deadLetterFile),
            maxAttempts = 1,
        )

        sink.writeAll(listOf(event("e1")))

        assertEquals(1, server.requestCount)
        assertEquals(listOf("e1"), Files.readAllLines(deadLetterFile).map { JsonEventCodec.decode(it).id })
        sink.close()
    }

    @Test
    fun `circuit breaker stops requests after repeated failures and recovers after cooldown`() = runBlocking {
        val clock = FixedClock(0)
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200))
        val spool = FileRemoteSpool(folder.newFile("cb.jsonl").toPath())
        val sink = sink(
            spool = spool,
            maxAttempts = 1,
            circuitBreaker = RemoteCircuitBreakerConfig(failureThreshold = 2, openMillis = 1_000),
            clock = clock,
        )

        sink.writeAll(listOf(event("e1")))
        sink.writeAll(listOf(event("e2")))
        assertEquals(2, server.requestCount)
        takeRequest()
        takeRequest()

        sink.writeAll(listOf(event("e3")))
        assertEquals("open circuit must not call the transport", 2, server.requestCount)
        assertEquals(3, spool.peek(10).size)

        clock.set(1_000)
        sink.flush()
        assertEquals(3, server.requestCount)
        val recovered = takeRequest().gunzipped().trim().lines()
        assertEquals(listOf("e1", "e2", "e3"), recovered.map { JsonEventCodec.decode(it).id })
        assertTrue(spool.peek(10).isEmpty())
        sink.close()
    }

    @Test
    fun `flush drains the spool before close`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200))
        val spool = FileRemoteSpool(folder.newFile("shutdown.jsonl").toPath())
        val sink = sink(spool = spool, maxAttempts = 1)

        sink.writeAll(listOf(event("e1")))
        assertEquals(1, spool.peek(10).size)

        sink.flush()
        assertTrue(spool.peek(10).isEmpty())
        assertEquals(2, server.requestCount)
        sink.close()
    }

    @Test
    fun `a full spool is reported and stops the write`() = runBlocking {
        val spool = FileRemoteSpool(folder.newFile("small.jsonl").toPath(), maxBytes = 8)
        val errors = ArrayList<Throwable>()
        val sink = sink(spool = spool, onError = { errors += it })

        sink.writeAll(listOf(event("e1")))

        assertEquals(0, server.requestCount)
        assertTrue(errors.single() is RemoteSpoolFullException)
        sink.close()
    }

    @Test
    fun `a failing header provider never sends and keeps the batch spooled`() = runBlocking {
        val spool = FileRemoteSpool(folder.newFile("auth.jsonl").toPath())
        val errors = ArrayList<Throwable>()
        val sink = sink(
            spool = spool,
            maxAttempts = 1,
            headers = { throw IllegalStateException("token service down") },
            onError = { errors += it },
        )

        sink.writeAll(listOf(event("e1")))

        assertEquals(0, server.requestCount)
        assertEquals(1, spool.peek(10).size)
        assertTrue(errors.any { it.message!!.contains("token service down") })
        sink.close()
    }
}

class RemoteConfigValidationTest {

    private val transport = RemoteTransport { RemoteResponse(200) }

    @Test
    fun `config rejects invalid limits`() {
        val invalid = listOf<() -> Unit>(
            { RemoteLogSinkConfig(transport, maxBatchSize = 0) },
            { RemoteLogSinkConfig(transport, maxBatchSize = 10_001) },
            { RemoteLogSinkConfig(transport, maxAttempts = 0) },
            { RemoteLogSinkConfig(transport, maxAttempts = 101) },
            { RemoteLogSinkConfig(transport, retryBaseMillis = -1) },
            { RemoteLogSinkConfig(transport, retryBaseMillis = 10, retryMaxMillis = 5) },
            { RemoteCircuitBreakerConfig(failureThreshold = 0) },
            { RemoteCircuitBreakerConfig(openMillis = 0) },
        )
        invalid.forEach { block ->
            try {
                block()
                fail("Expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `response classification covers success retryable and permanent`() {
        assertTrue(RemoteResponse(200).isSuccess)
        assertTrue(RemoteResponse(204).isSuccess)
        assertFalse(RemoteResponse(200).isRetryable)

        assertTrue(RemoteResponse(429).isRetryable)
        assertTrue(RemoteResponse(408).isRetryable)
        assertTrue(RemoteResponse(500).isRetryable)
        assertTrue(RemoteResponse(503).isRetryable)

        assertTrue(RemoteResponse(400).isPermanent)
        assertTrue(RemoteResponse(404).isPermanent)
        assertFalse(RemoteResponse(400).isRetryable)
        assertFalse(RemoteResponse(500).isPermanent)
    }
}

class JavaHttpTransportTest {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun batch(body: String = "[]\n"): RemoteBatch = RemoteBatch(
        batchId = "batch-1",
        lines = listOf(body.trim()),
        body = body.toByteArray(),
        contentType = "application/x-ndjson; charset=utf-8",
        contentEncoding = null,
        headers = mapOf("X-Test" to "yes"),
    )

    @Test
    fun `transport maps statuses headers and retry-after`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        val transport = JavaHttpTransport(server.url("/ingest").toString())

        val response = transport.send(batch())

        assertEquals(429, response.status)
        assertEquals(2_000L, response.retryAfterMillis)
        assertTrue(response.isRetryable)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("batch-1", request.getHeader("Idempotency-Key"))
        assertEquals("yes", request.getHeader("X-Test"))
        assertEquals("POST", request.method)
    }

    @Test
    fun `connection failures become transport exceptions`() = runBlocking {
        val transport = JavaHttpTransport(server.url("/gone").toString(), requestTimeout = Duration.ofMillis(500))
        server.shutdown()

        try {
            transport.send(batch())
            fail("Expected RemoteTransportException")
        } catch (_: RemoteTransportException) {
            // expected
        }
    }

    @Test
    fun `endpoint and timeout validation`() {
        val invalid = listOf<() -> Unit>(
            { JavaHttpTransport("ftp://example.com/logs") },
            { JavaHttpTransport("not a url") },
            { JavaHttpTransport(server.url("/logs").toString(), requestTimeout = Duration.ZERO) },
        )
        invalid.forEach { block ->
            try {
                block()
                fail("Expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}

class FileRemoteSpoolTest {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `append peek remove and size`() = runBlocking {
        val file: Path = folder.newFile("spool.jsonl").toPath()
        val spool = FileRemoteSpool(file, maxBytes = 1_000)

        assertTrue(spool.append(listOf("a", "b", "c")))
        assertEquals(6L, spool.sizeBytes())
        assertEquals(listOf("a", "b"), spool.peek(2))
        assertEquals(listOf("a", "b", "c"), spool.peek(10))

        spool.removeFirst(2)
        assertEquals(listOf("c"), spool.peek(10))
        spool.removeFirst(5)
        assertEquals(emptyList<String>(), spool.peek(10))
        assertFalse(Files.exists(file))
    }

    @Test
    fun `append refuses to exceed maxBytes and validates input`() = runBlocking {
        val file = folder.newFile("bounded.jsonl").toPath()
        val spool = FileRemoteSpool(file, maxBytes = 5)

        assertTrue(spool.append(listOf("abcd")))
        assertFalse(spool.append(listOf("ef")))
        assertTrue(spool.append(emptyList()))

        try {
            spool.append(listOf("two\nlines"))
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `missing file behaves as an empty spool`() = runBlocking {
        val spool = FileRemoteSpool(folder.root.toPath().resolve("nested/never-created.jsonl"))

        assertEquals(emptyList<String>(), spool.peek(5))
        assertEquals(0L, spool.sizeBytes())
        spool.removeFirst(3)
    }
}
