package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {

    private fun context(
        action: String,
        level: LogLevel = LogLevel.INFO,
        category: String? = null,
    ) = SamplingContext(level = level, action = action, category = category)

    @Test
    fun `contextual sampler receives the real action, category and outcome`() = runBlocking {
        val seen = Collections.synchronizedList(ArrayList<SamplingContext>())
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            contextualSampler = ContextualSampler { context ->
                seen += context
                true
            }
        }

        logger.use {
            logger.record(
                action = "task.created",
                level = LogLevel.INFO,
                category = "workflows",
                outcome = Outcome.FAILURE,
                failure = IllegalStateException("nope"),
                metadata = mapOf("title" to "Write docs"),
            )
            assertTrue(logger.awaitIdle(5_000))
        }

        val sampling = seen.single()
        assertEquals("task.created", sampling.action)
        assertEquals(LogLevel.INFO, sampling.level)
        assertEquals("workflows", sampling.category)
        assertEquals(Outcome.FAILURE, sampling.outcome)
        assertEquals(mapOf("title" to "Write docs"), sampling.metadata)
        assertEquals(1, sink.count())
    }

    @Test
    fun `audit events bypass the sampler unless auditSampling is enabled`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            contextualSampler = ContextualSampler {
                calls.incrementAndGet()
                false
            }
        }

        logger.use {
            logger.record(action = "audit.event")
            assertTrue(logger.awaitIdle(5_000))
            assertEquals("audit must not be sampled by default", 1, sink.count())
            assertEquals(0, calls.get())
        }

        val sampledSink = RecordingSink()
        val sampled = ILogger.create {
            sink(sampledSink)
            auditSampling = true
            contextualSampler = ContextualSampler {
                calls.incrementAndGet()
                false
            }
        }
        sampled.use {
            it.record(action = "audit.event")
            assertTrue(it.awaitIdle(5_000))
        }
        assertEquals(0, sampledSink.count())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a correlated operation shares one sampling decision across outcomes`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            contextualSampler = ContextualSampler {
                calls.incrementAndGet()
                false
            }
        }

        logger.use {
            withCorrelation("operation-1") {
                logger.record(action = "checkout.step", level = LogLevel.INFO, outcome = Outcome.START)
                logger.record(action = "checkout.step", level = LogLevel.INFO, outcome = Outcome.SUCCESS)
                logger.record(action = "checkout.step", level = LogLevel.ERROR, outcome = Outcome.FAILURE)
            }
            assertTrue(logger.awaitIdle(5_000))
            assertEquals("START/SUCCESS/FAILURE share one decision", 1, calls.get())

            withCorrelation("operation-1") {
                logger.record(action = "checkout.other", level = LogLevel.INFO)
            }
            assertTrue(logger.awaitIdle(5_000))
            assertEquals("different actions decide independently", 2, calls.get())

            withCorrelation("operation-2") {
                logger.record(action = "checkout.step", level = LogLevel.INFO)
            }
            assertTrue(logger.awaitIdle(5_000))
            assertEquals("different operations decide independently", 3, calls.get())
            assertEquals(0, sink.count())
        }
    }

    @Test
    fun `uncorrelated events are sampled independently`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val logger = ILogger.create {
            sink(RecordingSink())
            contextualSampler = ContextualSampler {
                calls.incrementAndGet()
                true
            }
        }

        logger.use {
            logger.record(action = "plain.one", level = LogLevel.INFO)
            logger.record(action = "plain.one", level = LogLevel.INFO)
            assertTrue(logger.awaitIdle(5_000))
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun `legacy log samplers receive the real action through the adapter`() = runBlocking {
        val sink = RecordingSink()
        val logger = ILogger.create {
            sink(sink)
            sampler = LogSampler { _, action -> action != "skip.me" }
            dispatch = DispatchMode.ASYNC
        }

        logger.use {
            logger.record(action = "skip.me", level = LogLevel.INFO)
            logger.record(action = "keep.me", level = LogLevel.INFO)
            assertTrue(logger.awaitIdle(5_000))
        }
        assertEquals(listOf("keep.me"), sink.actions())
    }

    @Test
    fun `isLoggable reflects level, sampler and enabled state`() {
        val logger = ILogger.create {
            sink(RecordingSink())
            minLevel = LogLevel.WARN
            contextualSampler = ContextualSampler { context -> context.action != "quiet.action" }
        }

        assertFalse(logger.isLoggable(LogLevel.DEBUG, "any.action"))
        assertTrue(logger.isLoggable(LogLevel.WARN, "any.action"))
        assertTrue(logger.isLoggable(LogLevel.AUDIT, "quiet.action"))
        assertFalse(logger.isLoggable(LogLevel.INFO, "quiet.action"))

        logger.enabled = false
        assertFalse(logger.isLoggable(LogLevel.AUDIT, "any.action"))
        logger.close()
    }

    @Test
    fun `action sampler keys decisions by action with deterministic rates`() {
        val sampler = ActionSampler(mapOf("noisy.action" to 0.0, "important.action" to 1.0))

        assertFalse(sampler.shouldSample(context("noisy.action")))
        assertTrue(sampler.shouldSample(context("important.action")))
        assertTrue(sampler.shouldSample(context("unlisted.action")))
    }

    @Test
    fun `category sampler falls back to its default rate`() {
        val sampler = CategorySampler(mapOf("http" to 0.0), defaultRate = 1.0)

        assertFalse(sampler.shouldSample(context("request", category = "http")))
        assertTrue(sampler.shouldSample(context("request", category = "workflows")))
        assertTrue(sampler.shouldSample(context("request", category = null)))
    }

    @Test
    fun `rate limit sampler honors boundary rates and injected randomness`() {
        assertFalse(RateLimitSampler(0.0).shouldSample(context("any")))
        assertTrue(RateLimitSampler(1.0).shouldSample(context("any")))

        val first = RateLimitSampler(0.5, Random(7))
        val second = RateLimitSampler(0.5, Random(7))
        val sequenceA = List(20) { first.shouldSample(context("any")) }
        val sequenceB = List(20) { second.shouldSample(context("any")) }
        assertEquals(sequenceA, sequenceB)
        assertTrue("a 0.5 rate must accept some events", sequenceA.any { it })
        assertTrue("a 0.5 rate must reject some events", sequenceA.any { !it })
    }

    @Test
    fun `sampling builder helper accepts contextual samplers`() {
        val logger = ILogger.create {
            sink(RecordingSink())
            sampling(ActionSampler(mapOf("blocked.action" to 0.0)))
        }
        assertFalse(logger.isLoggable(LogLevel.INFO, "blocked.action"))
        assertTrue(logger.isLoggable(LogLevel.INFO, "allowed.action"))
        logger.close()
    }
}
