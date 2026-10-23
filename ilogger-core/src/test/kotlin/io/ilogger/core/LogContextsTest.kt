package io.ilogger.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogContextsTest {

    @Test
    fun `default context is empty`() = runBlocking {
        assertEquals(LogContext(), LogContexts.current())
        assertEquals(LogContext(), LogContexts.currentThread())
    }

    @Test
    fun `nested scopes merge scalars and accumulate attributes`() = runBlocking {
        withActor(Actor.user("u1")) {
            withCorrelation("corr-1") {
                withContextAttributes("tenant" to "acme") {
                    val outer = LogContexts.current()
                    assertEquals("u1", outer.actor?.id)
                    assertEquals("corr-1", outer.correlationId)
                    assertEquals(mapOf("tenant" to "acme"), outer.attributes)

                    withActor(Actor.worker("w1")) {
                        val inner = LogContexts.current()
                        assertEquals("w1", inner.actor?.id)
                        assertEquals("corr-1", inner.correlationId)
                        assertEquals("acme", inner.attributes["tenant"])
                    }

                    assertEquals("u1", LogContexts.current().actor?.id)
                }
            }
        }
        assertEquals(LogContext(), LogContexts.current())
    }

    @Test
    fun `causation is attached and restored`() = runBlocking {
        withCausation("event-7") {
            assertEquals("event-7", LogContexts.current().causationId)
        }
        assertNull(LogContexts.current().causationId)
    }

    @Test
    fun `thread bridge restores the previous context`() {
        val outer = LogContext(correlationId = "outer")
        LogContexts.onCurrentThread(outer) {
            assertEquals("outer", LogContexts.currentThread().correlationId)

            LogContexts.onCurrentThread(LogContext(actor = Actor.user("inner"))) {
                assertEquals("inner", LogContexts.currentThread().actor?.id)
                assertNull(LogContexts.currentThread().correlationId)
            }

            assertEquals("outer", LogContexts.currentThread().correlationId)
            assertNull(LogContexts.currentThread().actor)
        }
        assertEquals(LogContext(), LogContexts.currentThread())
    }

    @Test
    fun `empty context on the thread bridge keeps the current one`() {
        LogContexts.onCurrentThread(LogContext(correlationId = "outer")) {
            LogContexts.onCurrentThread(LogContext()) {
                assertEquals("outer", LogContexts.currentThread().correlationId)
            }
            assertEquals("outer", LogContexts.currentThread().correlationId)
        }
    }

    @Test
    fun `suspend current falls back to the thread bridge`() = runBlocking {
        LogContexts.onCurrentThread(LogContext(correlationId = "thread-corr")) {
            assertEquals("thread-corr", LogContexts.current().correlationId)
        }
    }

    @Test
    fun `coroutine context wins over the thread bridge`() = runBlocking {
        LogContexts.onCurrentThread(LogContext(correlationId = "thread-corr")) {
            withCorrelation("coroutine-corr") {
                assertEquals("coroutine-corr", LogContexts.current().correlationId)
            }
        }
    }

    @Test
    fun `concurrent coroutines never observe each other's context`() = runBlocking {
        val mismatches = AtomicInteger()
        coroutineScope {
            repeat(50) { index ->
                launch(Dispatchers.Default) {
                    withActor(Actor.user("u$index")) {
                        delay(1)
                        val seen = LogContexts.current().actor?.id
                        if (seen != "u$index") mismatches.incrementAndGet()
                    }
                }
            }
        }
        assertEquals(0, mismatches.get())
    }

    @Test
    fun `equals and hashCode cover every field`() {
        val first = LogContext(
            actor = Actor.user("u1"),
            correlationId = "c",
            causationId = "cause",
            attributes = mapOf("tenant" to "acme"),
            suppressSources = setOf("TaskRepository.find"),
        )
        val same = LogContext(
            actor = Actor.user("u1"),
            correlationId = "c",
            causationId = "cause",
            attributes = mapOf("tenant" to "acme"),
            suppressSources = setOf("TaskRepository.find"),
        )

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
    }
}
