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
 * - scores each phase by the fastest of [iterations] warm runs, because
 *   background work only ever adds time;
 * - subtracts the empty-loop baseline from the disabled path;
 * - allows [Budgets.REGRESSION_TOLERANCE] overshoot;
 * - measures a path up to [MEASUREMENTS] times, stopping at the first
 *   measurement inside budget, so a contended runner cannot redden a build
 *   while a real slowdown — which breaches every measurement — still does.
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

        val baselineNs = bestPerOp { }
        val paths = listOf(
            ProducerPath("disabled", Budgets.DISABLED_NANOS) { measureDisabled(baselineNs) },
            ProducerPath("async enqueue", Budgets.ASYNC_ENQUEUE_NANOS) { measureAsyncEnqueue() },
            ProducerPath("buffered audit", Budgets.BUFFERED_AUDIT_NANOS) { measureBufferedEnqueue() },
        )

        val reported = LinkedHashMap<String, Double>()
        val failures = ArrayList<String>()
        for (path in paths) {
            // A contended runner inflates whole phases; the host observed here
            // spans 950–2340 ns/op for the same code. Only a slowdown that
            // breaches every measurement is a regression, so measuring stops at
            // the first pass.
            var best = Double.MAX_VALUE
            var attempts = 0
            while (attempts < MEASUREMENTS && Budgets.isRegression(best, path.budgetNs)) {
                val measured = path.measure()
                attempts++
                best = minOf(best, measured)
            }
            reported[path.label] = best
            if (Budgets.isRegression(best, path.budgetNs)) {
                failures += Budgets.describe(path.label, best, path.budgetNs) +
                    " in all $attempts measurements"
            }
        }
        // Printed on every run and carried in the assertion message, so a CI run
        // shows its measurements whether it passes or fails.
        val summary = "budget gate: " + paths.joinToString(" | ") {
            "${it.label}=${ns(reported.getValue(it.label))} (budget ${ns(it.budgetNs)})"
        } + " | baseline=${ns(baselineNs)}"
        println(summary)
        assertTrue("$summary\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private class ProducerPath(
        val label: String,
        val budgetNs: Double,
        val measure: suspend () -> Double,
    )

    private companion object {
        const val MEASUREMENTS = 3
    }

    private suspend fun measureDisabled(baselineNs: Double): Double {
        val logger = ILogger.create {
            sink(NopSink)
            enabled = false
        }
        try {
            return (bestPerOp { logger.info("gate message") } - baselineNs).coerceAtLeast(0.0)
        } finally {
            logger.close()
        }
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
            return bestPerOp {
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
            return bestPerOp {
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

    private suspend fun bestPerOp(block: suspend (Int) -> Unit): Double {
        repeat(warmups) { loop(block) }
        val samples = List(iterations) { loop(block) / n.toDouble() }.sorted()
        // Background work only ever adds time, so the fastest full phase is the
        // least contaminated estimate of the code's own cost.
        return samples.first()
    }

    private suspend fun loop(block: suspend (Int) -> Unit): Long {
        val start = System.nanoTime()
        for (i in 0 until n) block(i)
        return System.nanoTime() - start
    }

    private fun ns(value: Double): String = "%.2f".format(value)
}
