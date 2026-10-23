package io.ilogger.ktor

import io.ilogger.core.Actor
import io.ilogger.core.LogClock
import io.ilogger.core.Outcome
import io.ilogger.testing.TestKit
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAuditTest {

    @Test
    fun `records one success event with request metadata`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) { logger = kit.logger }
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }

        val response = client.get("/ping") {
            header("X-Request-Id", "req-1")
            header("X-User-Id", "u1")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(kit.sink.awaitCount(1))
        val event = kit.sink.last()!!
        assertEquals("http.request", event.action)
        assertEquals(Outcome.SUCCESS, event.outcome)
        assertEquals("HttpRequest", event.entity)
        assertEquals("req-1", event.entityId)
        assertEquals(Actor.user("u1"), event.actor)
        assertEquals("ktor.server", event.source)
        assertEquals("http", event.category)
        assertEquals("GET", event.metadata["method"])
        assertEquals("/ping", event.metadata["path"])
        assertEquals(200, event.metadata["status"])
        assertTrue((event.metadata["durationMillis"] as Long) >= 0)
        kit.logger.close()
    }

    @Test
    fun `generates and echoes a correlation id when the header is absent`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) { logger = kit.logger }
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }

        val response = client.get("/ping")

        val generated = response.headers["X-Request-Id"]
        assertNotNull(generated)
        assertTrue(kit.sink.awaitCount(1))
        assertEquals(generated, kit.sink.last()!!.entityId)
        kit.logger.close()
    }

    @Test
    fun `duration comes from the injected clock`() = testApplication {
        val kit = TestKit.create()
        val ticks = java.util.concurrent.atomic.AtomicLong(0)
        application {
            install(HttpAudit) {
                logger = kit.logger
                clock = LogClock { ticks.addAndGet(10) }
            }
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }

        val response = client.get("/ping")
        kit.logger.flush()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(kit.sink.awaitCount(1))
        assertEquals(10L, kit.sink.last()!!.metadata["durationMillis"])
        kit.logger.close()
    }

    @Test
    fun `excluded paths are not audited`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) { logger = kit.logger }
            routing {
                get("/health") { call.respondText("ok") }
            }
        }

        val response = client.get("/health")
        kit.logger.flush()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, kit.sink.count())
        kit.logger.close()
    }

    @Test
    fun `failed requests are audited as failures with the cause`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) { logger = kit.logger }
            routing {
                get("/boom") { error("kaboom") }
            }
        }

        val response = client.get("/boom") { header("X-Request-Id", "req-fail") }
        kit.logger.flush()

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val events = kit.sink.events().filter { it.action == "http.request" }
        // Ktor 3.6 routes a thrown handler failure through CallFailed only; the
        // response hook does not also fire in the test host.
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(Outcome.FAILURE, event.outcome)
        assertEquals(io.ilogger.annotations.LogLevel.ERROR, event.level)
        assertNotNull("the structured cause must be attached", event.failure)
        assertEquals("req-fail", event.entityId)
        kit.logger.close()
    }

    @Test
    fun `withAuditContext propagates correlation and actor to downstream events`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) { logger = kit.logger }
            routing {
                get("/work") {
                    call.withAuditContext(Actor.worker("w1"), "tenant" to "acme") {
                        kit.logger.record(action = "downstream.event")
                    }
                    call.respondText("done")
                }
            }
        }

        val response = client.get("/work") { header("X-Request-Id", "req-ctx") }
        kit.logger.flush()

        assertEquals(HttpStatusCode.OK, response.status)
        val downstream = kit.sink.events().first { it.action == "downstream.event" }
        assertEquals("req-ctx", downstream.correlationId)
        assertEquals(Actor.worker("w1"), downstream.actor)
        kit.logger.close()
    }

    @Test
    fun `requestFilter excludes matching calls but still echoes correlation`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) {
                logger = kit.logger
                requestFilter = { call -> call.request.path() != "/skip" }
            }
            routing {
                get("/skip") { call.respondText("skipped") }
                get("/keep") { call.respondText("kept") }
            }
        }

        val skipped = client.get("/skip")
        val kept = client.get("/keep")

        assertNotNull(skipped.headers["X-Request-Id"])
        assertNotNull(kept.headers["X-Request-Id"])
        assertTrue(kit.sink.awaitCount(1))
        kit.logger.flush()
        val events = kit.sink.events().filter { it.action == "http.request" }
        assertEquals(1, events.size)
        assertEquals("/keep", events.single().metadata["path"])
        kit.logger.close()
    }

    @Test
    fun `sampling zero records no events but establishes context`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) {
                logger = kit.logger
                sampling = 0.0
            }
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }

        val response = client.get("/ping")

        assertNotNull(response.headers["X-Request-Id"])
        kit.logger.flush()
        assertEquals(0, kit.sink.count())
        kit.logger.close()
    }

    @Test
    fun `both failure mode records status failures and handler failures`() = testApplication {
        val kit = TestKit.create()
        application {
            install(HttpAudit) {
                logger = kit.logger
                failureEvents = HttpFailureEvents.BOTH
            }
            routing {
                get("/boom-status") {
                    call.respondText("unavailable", status = HttpStatusCode.InternalServerError)
                }
                get("/boom-handler") { error("kaboom") }
            }
        }

        val statusResponse = client.get("/boom-status") { header("X-Request-Id", "req-status") }
        val handlerResponse = client.get("/boom-handler") { header("X-Request-Id", "req-handler") }
        kit.logger.flush()

        assertEquals(HttpStatusCode.InternalServerError, statusResponse.status)
        assertEquals(HttpStatusCode.InternalServerError, handlerResponse.status)
        val events = kit.sink.events().filter { it.action == "http.request" }
        val statusEvent = events.single { it.entityId == "req-status" }
        assertEquals(Outcome.FAILURE, statusEvent.outcome)
        assertEquals(500, statusEvent.metadata["status"])
        assertNull(statusEvent.failure)

        val handlerEvent = events.single { it.entityId == "req-handler" }
        assertEquals(Outcome.FAILURE, handlerEvent.outcome)
        assertEquals(500, handlerEvent.metadata["status"])
        assertNotNull("the failure event carries the cause", handlerEvent.failure)
        kit.logger.close()
    }
}
