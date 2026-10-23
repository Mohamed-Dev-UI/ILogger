package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RateLimitingTest {

    private fun context(
        action: String = "any.action",
        level: LogLevel = LogLevel.INFO,
        category: String? = null,
        actor: Actor? = null,
    ) = SamplingContext(level = level, action = action, category = category, actor = actor)

    // ------------------------------------------------------------------
    // TokenBucketRateLimiter
    // ------------------------------------------------------------------

    @Test
    fun `token bucket admits the burst then refills at the configured rate`() {
        val clock = FixedClock(0)
        val limiter = TokenBucketRateLimiter(
            permitsPerSecond = 2.0,
            burst = 2,
            scope = RateLimitScope.ACTION,
            clock = clock,
        )

        assertTrue(limiter.shouldSample(context("a.one")))
        assertTrue(limiter.shouldSample(context("a.one")))
        assertFalse("burst exhausted", limiter.shouldSample(context("a.one")))

        assertTrue("other actions keep their own bucket", limiter.shouldSample(context("a.two")))

        clock.set(500)
        assertTrue(limiter.shouldSample(context("a.one")))
        assertFalse(limiter.shouldSample(context("a.one")))

        clock.set(2_000)
        assertTrue(limiter.shouldSample(context("a.one")))
        assertTrue(limiter.shouldSample(context("a.one")))
        assertFalse("the bucket never refills beyond burst", limiter.shouldSample(context("a.one")))

        val stats = limiter.stats()
        assertEquals(9, stats.considered)
        assertEquals(6, stats.sampled)
        assertEquals(3, stats.skipped)
        assertEquals(2.0, stats.currentRate, 0.0)
        assertEquals(2, stats.buckets)
    }

    @Test
    fun `scope selects the keying dimension`() {
        val clock = FixedClock(0)

        val byLevel = TokenBucketRateLimiter(1.0, burst = 1, scope = RateLimitScope.LEVEL, clock = clock)
        assertTrue(byLevel.shouldSample(context("action.one", level = LogLevel.INFO)))
        assertFalse("same level shares a bucket regardless of action", byLevel.shouldSample(context("action.two", level = LogLevel.INFO)))
        assertTrue(byLevel.shouldSample(context("action.one", level = LogLevel.WARN)))

        val byCategory = TokenBucketRateLimiter(1.0, burst = 1, scope = RateLimitScope.CATEGORY, clock = clock)
        assertTrue(byCategory.shouldSample(context(category = "http")))
        assertFalse(byCategory.shouldSample(context(category = "http")))
        assertTrue(byCategory.shouldSample(context(category = "workflows")))
        assertTrue("uncategorized events share the catch-all bucket", byCategory.shouldSample(context(category = null)))
        assertFalse(byCategory.shouldSample(context(category = null)))

        val byActor = TokenBucketRateLimiter(1.0, burst = 1, scope = RateLimitScope.ACTOR, clock = clock)
        val alice = Actor.user("alice")
        val bob = Actor.user("bob")
        assertTrue(byActor.shouldSample(context(actor = alice)))
        assertFalse(byActor.shouldSample(context(actor = alice)))
        assertTrue(byActor.shouldSample(context(actor = bob)))
        assertTrue(byActor.shouldSample(context(actor = null)))

        val global = TokenBucketRateLimiter(1.0, burst = 1, scope = RateLimitScope.GLOBAL, clock = clock)
        assertTrue(global.shouldSample(context("one", level = LogLevel.INFO, category = "a", actor = alice)))
        assertFalse(global.shouldSample(context("two", level = LogLevel.WARN, category = "b", actor = bob)))
    }

    @Test
    fun `audit events bypass the limiter and its counters`() {
        val limiter = TokenBucketRateLimiter(1.0, burst = 1, scope = RateLimitScope.GLOBAL)

        assertTrue(limiter.shouldSample(context("diag.one")))
        assertFalse(limiter.shouldSample(context("diag.two")))
        repeat(50) {
            assertTrue(limiter.shouldSample(context("audit.one", level = LogLevel.AUDIT)))
        }

        val stats = limiter.stats()
        assertEquals("audit never reaches bucket accounting", 2, stats.considered)
        assertEquals(1, stats.sampled)
        assertEquals(1, stats.skipped)
        assertEquals(1, stats.buckets)
    }

    @Test
    fun `bucket map is bounded by maxBuckets with LRU eviction`() {
        val limiter = TokenBucketRateLimiter(1.0, burst = 1, scope = RateLimitScope.ACTION, maxBuckets = 2)

        assertTrue(limiter.shouldSample(context("a")))
        assertTrue(limiter.shouldSample(context("b")))
        assertTrue(limiter.shouldSample(context("c")))
        assertEquals(2, limiter.stats().buckets)
        assertTrue("an evicted key starts with a fresh full bucket", limiter.shouldSample(context("a")))
        assertEquals(2, limiter.stats().buckets)
    }

    // ------------------------------------------------------------------
    // AdaptiveSampler
    // ------------------------------------------------------------------

    @Test
    fun `a diagnostic flood is bounded while audit flow is unaffected`() {
        val clock = FixedClock(0)
        val sampler = AdaptiveSampler(
            targetEventsPerSecond = 1_000.0,
            clock = clock,
            random = Random(7),
        )
        var diagnostics = 0
        var audits = 0

        for (i in 0 until 100_000) {
            clock.set((i / 100).toLong()) // 100 events per simulated millisecond = 100k/s
            if (sampler.shouldSample(context("flood.event"))) diagnostics++
            if (i % 1_000 == 0 && sampler.shouldSample(context("audit.event", level = LogLevel.AUDIT))) audits++
        }

        assertEquals("every audit event passes", 100, audits)
        assertTrue("diagnostics are bounded to burst + one second of refill, got $diagnostics", diagnostics in 1_900..2_100)
        val stats = sampler.stats()
        assertEquals("only diagnostics are considered", 100_000, stats.considered)
        assertEquals(diagnostics.toLong(), stats.sampled)
        assertEquals((100_000 - diagnostics).toLong(), stats.skipped)
    }

    @Test
    fun `adaptive rate sheds after a measured overload and recovers when quiet`() {
        val clock = FixedClock(0)
        val sampler = AdaptiveSampler(
            targetEventsPerSecond = 1_000.0,
            clock = clock,
            random = Random(11),
        )

        for (i in 0 until 100_000) {
            clock.set((i / 100).toLong())
            sampler.shouldSample(context("flood.event"))
        }
        assertEquals("still in the first window", 1.0, sampler.stats().currentRate, 0.0)

        clock.set(1_000)
        sampler.shouldSample(context("flood.event"))
        val overloaded = sampler.stats()
        assertEquals(100_000.0, overloaded.observedPerSecond, 0.0)
        assertEquals(0.01, overloaded.currentRate, 1e-9)

        clock.set(3_000)
        assertTrue("a quiet window restores full admission", sampler.shouldSample(context("flood.event")))
        assertEquals(1.0, sampler.stats().currentRate, 0.0)
    }

    @Test
    fun `adaptive decisions are reproducible with an injected random`() {
        fun run(): List<Boolean> {
            val clock = FixedClock(0)
            val sampler = AdaptiveSampler(
                targetEventsPerSecond = 1.0,
                clock = clock,
                random = Random(42),
                windowMillis = 1_000,
            )
            val decisions = ArrayList<Boolean>()
            for (i in 0 until 40) {
                clock.set((i / 4).toLong() * 1_000) // four offers per simulated second
                decisions += sampler.shouldSample(context("flood.event"))
            }
            return decisions
        }

        val first = run()
        val second = run()
        assertEquals(first, second)
        assertTrue("some events are shed under overload", first.any { !it })
        assertTrue("the sampler is not a constant", first.toSet().size > 1)
    }

    // ------------------------------------------------------------------
    // Composition and engine integration
    // ------------------------------------------------------------------

    @Test
    fun `composite sampler requires every delegate to accept`() {
        val accept = ContextualSampler { true }
        val deny = ContextualSampler { false }

        assertTrue(CompositeSampler(accept, accept).shouldSample(context()))
        assertFalse(CompositeSampler(accept, deny).shouldSample(context()))
        assertFalse(CompositeSampler(deny, accept).shouldSample(context()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `composite sampler rejects an empty delegate list`() {
        CompositeSampler(emptyList())
    }

    @Test
    fun `samplers reject invalid construction parameters`() {
        val target = assertThrows(IllegalArgumentException::class.java) { AdaptiveSampler(0.0) }
        assertTrue(target.message!!.contains("targetEventsPerSecond"))

        assertThrows(IllegalArgumentException::class.java) { AdaptiveSampler(1.0, windowMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { AdaptiveSampler(1.0, minRate = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { TokenBucketRateLimiter(0.0) }
        assertThrows(IllegalArgumentException::class.java) { TokenBucketRateLimiter(1.0, burst = 0) }
        assertThrows(IllegalArgumentException::class.java) { TokenBucketRateLimiter(1.0, maxBuckets = 0) }
    }

    @Test
    fun `engine applies the limiter to diagnostics and exempts audit`() = runBlocking {
        val sink = RecordingSink()
        val limiter = TokenBucketRateLimiter(1.0, burst = 1, scope = RateLimitScope.GLOBAL)
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
            sampling(limiter)
        }

        logger.use {
            it.record(action = "diag.one", level = LogLevel.INFO)
            it.record(action = "diag.two", level = LogLevel.INFO)
            it.record(action = "audit.one")
        }

        assertEquals(listOf("diag.one", "audit.one"), sink.actions())
        assertEquals(2, limiter.stats().considered)
        assertEquals(1, limiter.stats().sampled)
        assertEquals(1, limiter.stats().skipped)
    }
}
