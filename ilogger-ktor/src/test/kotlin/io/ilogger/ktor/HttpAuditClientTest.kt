package io.ilogger.ktor

import io.ilogger.core.Actor
import io.ilogger.core.Outcome
import io.ilogger.core.withContextAttributes
import io.ilogger.core.withCorrelation
import io.ilogger.testing.TestKit
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpAuditClientTest {

    @Test
    fun `records one outbound event with method path status and duration`() = runBlocking {
        val kit = TestKit.create()
        val client = HttpClient(MockEngine { respondOk("ok") }) {
            install(HttpAuditClient) { logger = kit.logger }
        }

        client.get("https://inventory.example/stock?item=1")
        kit.logger.flush()

        val event = kit.sink.events().single { it.action == "http.client.request" }
        assertEquals(Outcome.SUCCESS, event.outcome)
        assertEquals("ktor.client", event.source)
        assertEquals("http", event.category)
        assertEquals("GET", event.metadata["method"])
        assertEquals("inventory.example", event.metadata["host"])
        assertEquals("/stock", event.metadata["path"])
        assertEquals(200, event.metadata["status"])
        assertTrue((event.metadata["durationMillis"] as Long) >= 0)
        assertNull(event.failure)
        client.close()
        kit.logger.close()
    }

    @Test
    fun `propagates ambient correlation and actor to the request and event`() = runBlocking {
        val kit = TestKit.create()
        val captured = AtomicReference<String?>()
        val client = HttpClient(MockEngine { request ->
            captured.set(request.headers["X-Request-Id"])
            respondOk("ok")
        }) {
            install(HttpAuditClient) { logger = kit.logger }
        }

        withCorrelation("req-42") {
            io.ilogger.core.withActor(Actor.worker("w1")) {
                client.get("https://inventory.example/stock")
            }
        }
        kit.logger.flush()

        assertEquals("req-42", captured.get())
        val event = kit.sink.events().single { it.action == "http.client.request" }
        assertEquals("req-42", event.entityId)
        assertEquals("req-42", event.correlationId)
        assertEquals(Actor.worker("w1"), event.actor)
        client.close()
        kit.logger.close()
    }

    @Test
    fun `an existing correlation header wins and is not overwritten`() = runBlocking {
        val kit = TestKit.create()
        val captured = AtomicReference<String?>()
        val client = HttpClient(MockEngine { request ->
            captured.set(request.headers["X-Request-Id"])
            respondOk("ok")
        }) {
            install(HttpAuditClient) { logger = kit.logger }
        }

        withCorrelation("ambient") {
            client.get("https://inventory.example/stock") { header("X-Request-Id", "explicit") }
        }
        kit.logger.flush()

        assertEquals("explicit", captured.get())
        assertEquals("explicit", kit.sink.events().single { it.action == "http.client.request" }.entityId)
        client.close()
        kit.logger.close()
    }

    @Test
    fun `forwards an ambient traceparent header`() = runBlocking {
        val kit = TestKit.create()
        val captured = AtomicReference<String?>()
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val client = HttpClient(MockEngine { request ->
            captured.set(request.headers["traceparent"])
            respondOk("ok")
        }) {
            install(HttpAuditClient) { logger = kit.logger }
        }

        withContextAttributes("traceparent" to traceparent) {
            client.get("https://inventory.example/stock")
        }
        kit.logger.flush()

        assertEquals(traceparent, captured.get())
        client.close()
        kit.logger.close()
    }

    @Test
    fun `generateTraceparent mints a well-formed header`() = runBlocking {
        val kit = TestKit.create()
        val captured = AtomicReference<String?>()
        val client = HttpClient(MockEngine { request ->
            captured.set(request.headers["traceparent"])
            respondOk("ok")
        }) {
            install(HttpAuditClient) {
                logger = kit.logger
                generateTraceparent = true
            }
        }

        client.get("https://inventory.example/stock")
        kit.logger.flush()

        val generated = captured.get()
        assertNotNull(generated)
        assertTrue(
            "unexpected traceparent: $generated",
            Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(generated!!),
        )
        client.close()
        kit.logger.close()
    }

    @Test
    fun `http error statuses are recorded as failures with their status`() = runBlocking {
        val kit = TestKit.create()
        val client = HttpClient(MockEngine {
            respond(
                content = "nope",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }) {
            install(HttpAuditClient) { logger = kit.logger }
        }

        client.get("https://inventory.example/stock")
        kit.logger.flush()

        val event = kit.sink.events().single { it.action == "http.client.request" }
        assertEquals(Outcome.FAILURE, event.outcome)
        assertEquals(503, event.metadata["status"])
        assertNull("an HTTP response is not a transport failure", event.metadata["transport"])
        client.close()
        kit.logger.close()
    }

    @Test
    fun `transport errors are recorded with the cause and rethrown`() = runBlocking {
        val kit = TestKit.create()
        val client = HttpClient(MockEngine { throw IOException("offline") }) {
            install(HttpAuditClient) { logger = kit.logger }
        }

        try {
            withCorrelation("req-offline") {
                client.get("https://inventory.example/stock")
            }
            fail("the transport error must be rethrown")
        } catch (expected: IOException) {
            assertEquals("offline", expected.message)
        }
        kit.logger.flush()

        val event = kit.sink.events().single { it.action == "http.client.request" }
        assertEquals(Outcome.FAILURE, event.outcome)
        assertEquals("req-offline", event.entityId)
        assertEquals(true, event.metadata["transport"])
        assertNotNull(event.failure)
        assertEquals("offline", event.failure!!.message)
        client.close()
        kit.logger.close()
    }

    @Test
    fun `sampling zero suppresses the event but still propagates headers`() = runBlocking {
        val kit = TestKit.create()
        val captured = AtomicReference<String?>()
        val client = HttpClient(MockEngine { request ->
            captured.set(request.headers["X-Request-Id"])
            respondOk("ok")
        }) {
            install(HttpAuditClient) {
                logger = kit.logger
                sampling = 0.0
            }
        }

        withCorrelation("req-sampled-out") {
            client.get("https://inventory.example/stock")
        }
        kit.logger.flush()

        assertEquals("req-sampled-out", captured.get())
        assertEquals(0, kit.sink.count())
        client.close()
        kit.logger.close()
    }

    @Test
    fun `requestFilter suppresses recording and header injection`() = runBlocking {
        val kit = TestKit.create()
        val captured = AtomicReference<String?>()
        val client = HttpClient(MockEngine { request ->
            captured.set(request.headers["X-Request-Id"])
            respondOk("ok")
        }) {
            install(HttpAuditClient) {
                logger = kit.logger
                requestFilter = { false }
            }
        }

        withCorrelation("req-filtered") {
            client.get("https://inventory.example/stock")
        }
        kit.logger.flush()

        assertNull(captured.get())
        assertEquals(0, kit.sink.count())
        client.close()
        kit.logger.close()
    }

    @Test
    fun `client correlation is visible to the server plugin`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) { logger = kit.logger }
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }
        val auditClient = createClient {
            install(HttpAuditClient) { logger = kit.logger }
        }

        val response = withCorrelation("edge-1") { auditClient.get("/ping") }
        assertTrue(kit.sink.awaitCount(2))

        assertEquals(HttpStatusCode.OK, response.status)
        val events = kit.sink.events()
        val clientEvent = events.single { it.source == "ktor.client" }
        val serverEvent = events.single { it.source == "ktor.server" }
        assertEquals("edge-1", clientEvent.entityId)
        assertEquals("edge-1", serverEvent.entityId)
        assertEquals("/ping", serverEvent.metadata["path"])
        assertFalse(serverEvent.metadata.containsKey("transport"))
        kit.logger.close()
    }
}
