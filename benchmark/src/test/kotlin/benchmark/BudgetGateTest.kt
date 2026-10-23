package benchmark

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.ILogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Deterministic (non-JMH) regression gate for the producer-side budgets.
 *
 * Measuring on shared CI hardware is noisy, so the gate:
 * - runs only with `ILOGGER_BENCH_GATE=true` (the dedicated Linux CI job);
 * - uses the median of [ITERATIONS] warm runs;
 * - subtracts the empty-loop baseline from the disabled path;
 * - allows [Budgets.REGRESSION_TOLERANCE] overshoot.
 *
 * JMH (`EnginePathsBenchmarks`) remains the authoritative methodology; this
 * gate exists to make accidental slowdowns fail a build.
 */
class BudgetGateTest {

    private val n = 100_000
    private val warmups = 5
    private val iterations = 9

    @Test
    fun `producer paths stay within budget`() = runBlocking {
        assumeTrue(
            "Set ILOGGER_BENCH_GATE=true to enforce performance budgets",
            System.getenv("ILOGGER_BENCH_GATE") == "true",
        )

        val baselineNs = medianPerOp { }
        val disabled = ILogger.create {
            sink(NopSink)
            enabled = false
        }
        val disabledNs = (medianPerOp { disabled.info("gate message") } - baselineNs).coerceAtLeast(0.0)
        disabled.close()

        val asyncNs = measureAsyncEnqueue()

        val bufferedNs = measureBufferedEnqueue()

        val failures = listOf(
            Budgets.DISABLED_NANOS to disabledNs,
            Budgets.ASYNC_ENQUEUE_NANOS to asyncNs,
            Budgets.BUFFERED_AUDIT_NANOS to bufferedNs,
        ).mapNotNull { (budget, measured) ->
            if (Budgets.isRegression(measured, budget)) {
                Budgets.describe("producer path", measured, budget)
            } else {
                null
            }
        }
        val summary =
            "budget gate: disabled=%.2f ns (budget %.2f) | async enqueue=%.2f ns (budget %.2f) | " +
                "buffered audit=%.2f ns (budget %.2f) | baseline=%.2f ns"
        println(
            summary.format(
                disabledNs, Budgets.DISABLED_NANOS,
                asyncNs, Budgets.ASYNC_ENQUEUE_NANOS,
                bufferedNs, Budgets.BUFFERED_AUDIT_NANOS,
                baselineNs,
            ),
        )
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private suspend fun measureAsyncEnqueue(): Double {
        val logger = ILogger.create {
            sink(NopSink)
            dispatch = DispatchMode.ASYNC
            bufferSize = 8_192
            batchSize = 1_024
            flushIntervalMillis = 60_000
        }
        try {
            return medianPerOp {
                logger.recordNow(
                    action = "bench.op",
                    level = LogLevel.AUDIT,
                    entity = "Entity",
                    entityId = "42",
                    metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
                    source = "benchmark",
                )
            }
        } finally {
            logger.flush()
            logger.close()
        }
    }

    private suspend fun measureBufferedEnqueue(): Double {
        val logger = ILogger.create {
            sink(NopSink)
            dispatch = DispatchMode.BUFFERED
            bufferSize = 8_192
            batchSize = 1_024
            flushIntervalMillis = 60_000
        }
        try {
            return medianPerOp {
                logger.recordNow(
                    action = "bench.op",
                    level = LogLevel.AUDIT,
                    entity = "Entity",
                    entityId = "42",
                    metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
                    source = "benchmark",
                    dispatch = DispatchMode.BUFFERED,
                )
            }
        } finally {
            logger.flush()
            logger.close()
        }
    }

    private suspend fun medianPerOp(block: suspend (Int) -> Unit): Double {
        repeat(warmups) { loop(block) }
        val samples = List(iterations) { loop(block) / n.toDouble() }.sorted()
        return samples[samples.size / 2]
    }

    private suspend fun loop(block: suspend (Int) -> Unit): Long {
        val start = System.nanoTime()
        for (i in 0 until n) block(i)
        return System.nanoTime() - start
    }
}
