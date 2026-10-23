package io.ilogger.core

import io.ilogger.annotations.LogLevel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorRegistryTest {

    private class CountingExtractor(val id: Int) : LogValueExtractor<String> {
        override fun extract(value: String): Any? = value.length
    }

    @Test
    fun `fallback is constructed once and reused`() {
        val registry = ExtractorRegistry()
        val calls = AtomicInteger()
        val first = registry.get(CountingExtractor::class) {
            calls.incrementAndGet()
            CountingExtractor(1)
        }
        val second = registry.get(CountingExtractor::class) {
            calls.incrementAndGet()
            CountingExtractor(2)
        }

        assertSame(first, second)
        assertEquals(1, calls.get())
    }

    @Test
    fun `registered factory wins over the fallback`() {
        val registry = ExtractorRegistry()
        val registered = CountingExtractor(7)
        registry.register(CountingExtractor::class) { registered }
        val fallbackCalls = AtomicInteger()

        val resolved = registry.get(CountingExtractor::class) {
            fallbackCalls.incrementAndGet()
            CountingExtractor(0)
        }

        assertSame(registered, resolved)
        assertEquals(0, fallbackCalls.get())
    }

    @Test
    fun `registered instance is returned as-is`() {
        val registry = ExtractorRegistry()
        val instance = CountingExtractor(3)
        registry.register(CountingExtractor::class, instance)
        assertSame(instance, registry.get(CountingExtractor::class) { CountingExtractor(0) })
    }

    @Test
    fun `require throws with the registration recipe when nothing is registered`() {
        val registry = ExtractorRegistry()
        val error = assertThrows(IllegalStateException::class.java) {
            registry.require(CountingExtractor::class)
        }
        assertTrue(error.message.orEmpty().contains("no extractor is registered"))
        assertTrue(error.message.orEmpty().contains("CountingExtractor"))
        assertTrue(error.message.orEmpty().contains("extractor("))
    }

    @Test
    fun `registration after resolution is rejected`() {
        val registry = ExtractorRegistry()
        registry.get(CountingExtractor::class) { CountingExtractor(1) }
        val error = assertThrows(IllegalStateException::class.java) {
            registry.register(CountingExtractor::class) { CountingExtractor(2) }
        }
        assertTrue(error.message.orEmpty().contains("already resolved"))
    }

    @Test
    fun `a get fallback does not satisfy require`() {
        val registry = ExtractorRegistry()
        registry.get(CountingExtractor::class) { CountingExtractor(1) }
        assertThrows(IllegalStateException::class.java) {
            registry.require(CountingExtractor::class)
        }
    }

    @Test
    fun `concurrent resolution yields exactly one instance`() {
        val registry = ExtractorRegistry()
        val calls = AtomicInteger()
        val threads = 8
        val start = CountDownLatch(1)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        val results = java.util.Collections.synchronizedList(ArrayList<CountingExtractor>())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    ready.countDown()
                    start.await()
                    results += registry.get(CountingExtractor::class) {
                        calls.incrementAndGet()
                        CountingExtractor(42)
                    }
                    done.countDown()
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            assertTrue("threads did not finish", done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads, results.size)
        assertEquals("factory must run once", 1, calls.get())
        assertTrue(results.all { it === results.first() })
    }

    @Test
    fun `isEmpty reflects registrations`() {
        val registry = ExtractorRegistry()
        assertTrue(registry.isEmpty)
        registry.register(CountingExtractor::class) { CountingExtractor(1) }
        assertFalse(registry.isEmpty)
    }

    @Test
    fun `validated contains throwing extractors`() {
        val extractor = LogValueExtractor<String> { error("boom") }.validated()
        assertNull(extractor.extract("value"))
    }

    @Test
    fun `validated omits non-loggable output`() {
        val extractor = LogValueExtractor<String> { Any() }.validated()
        assertNull("an arbitrary object is not log-safe", extractor.extract("value"))
    }

    @Test
    fun `validated accepts the expanded safe value model`() {
        val safe = listOf(
            "text",
            1,
            1L,
            2.5,
            true,
            'c',
            3u,
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Duration.ofHours(2),
            BigDecimal("19.99"),
            LogLevel.INFO,
            listOf("a", 1),
            mapOf("k" to listOf(1, 2)),
        )
        for (value in safe) {
            assertTrue("$value must be log-safe", LogSafeValues.isLogSafe(value))
            assertEquals(value, LogValueExtractor<String> { value }.validated().extract("ignored"))
        }
        assertTrue(LogSafeValues.isLogSafe(intArrayOf(1, 2)))
    }

    @Test
    fun `log safe values reject opaque objects and deep nesting`() {
        assertFalse(LogSafeValues.isLogSafe(Any()))
        assertFalse(LogSafeValues.isLogSafe(mapOf(1 to "non-string key")))
        assertFalse(LogSafeValues.isLogSafe(listOf(listOf(listOf(listOf(listOf("too deep")))))))
        assertTrue(LogSafeValues.isLogSafe(LogSafeValue.of(Any())))
    }

    @Test
    fun `the builder registry is exposed by the logger`() {
        val logger = ILogger.create {
            extractor(CountingExtractor::class) { CountingExtractor(5) }
        }
        logger.use {
            assertSame(it.extractors, it.extractors)
            assertEquals(5, it.extractors.get(CountingExtractor::class) { CountingExtractor(0) }.id)
        }
    }
}
