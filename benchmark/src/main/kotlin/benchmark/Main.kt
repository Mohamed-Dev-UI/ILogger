package benchmark

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.HashChainSink
import io.ilogger.core.HmacTrailSigner
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.ILogger
import io.ilogger.core.SigningKey
import io.ilogger.core.sink.TextFileSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.runBlocking

/**
 * Overhead measurement of the engine paths.
 *
 * Deterministic harness (for warm-cache runs): fixed warmup and measurement
 * iterations, per-iteration timing, median of iterations reported. The JMH
 * suite (`JmhEngines.kt`, `:benchmark:mainBenchmark`) is the CI-grade
 * methodology; this harness optimizes for a fast, dependency-free sanity run,
 * so its absolute numbers read higher (loop overhead is included by design).
 * They are precise enough to pin budgets: the annotation layer is designed so
 * that disabled/ineligible paths cost one volatile read.
 */
object Report {

    /** Median, p90 and max of a [-list] in nanoseconds. */
    fun summarize(label: String, ns: List<Long>): String {
        val sorted = ns.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size - 1).coerceAtLeast(0) * 90 / 100]
        return "%-42s median %,8d ns | p90 %,8d ns | max %,8d ns".format(label, median, p90, sorted.last())
    }

    suspend fun measure(warmup: Int, iterations: Int, block: suspend () -> Long): List<Long> {
        for (i in 0 until warmup) block()
        return List(iterations) { block() }
    }
}

/** Sink that absorbs events with the minimum possible work. */
object NopSink : LogSink {
    override suspend fun write(event: LogEvent) = Unit
}

private suspend fun runLoop(n: Int, block: suspend (Int) -> Unit): Long {
    val start = System.nanoTime()
    for (i in 0 until n) block(i)
    return (System.nanoTime() - start) / n
}

fun main() = runBlocking {
    val n = 100_000
    val warmup = 3
    val iterations = 7

    // ── Case 1: engine switched off (the cheapest path binding apps target) ────────
    val disabled = ILogger.create {
        sink(NopSink)
        enabled = false
    }
    val disabledNs = Report.measure(warmup, iterations) { runLoop(n) { i -> disabled.info("t$i") } }

    // ── Case 2: INFO through min-level gate + Inline dispatch + NopSink ──────────
    val inline = ILogger.create {
        sink(NopSink)
        dispatch = DispatchMode.INLINE
    }
    val infoNs = Report.measure(warmup, iterations) { runLoop(n) { i -> inline.info("t$i") } }

    // ── Case 3: suspend audit record() through Inline dispatch, 4 metadata ───────
    val auditNs = Report.measure(warmup, iterations) { runLoop(n) {
        i ->
        inline.record(
            action = "bench.op",
            level = LogLevel.AUDIT,
            entity = "Entity",
            entityId = i.toString(),
            metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
            source = "benchmark",
        )
    } }

    // ── Case 4: async recordNow into the pipeline (enqueue cost only) ─────────────
    val async = ILogger.create {
        sink(NopSink)
        dispatch = DispatchMode.ASYNC
        batchSize = 1024
        flushIntervalMillis = 1_000
    }
    val asyncNs = Report.measure(warmup, iterations) { runLoop(n) {
        i ->
        async.recordNow(
            action = "bench.op",
            level = LogLevel.AUDIT,
            entity = "Entity",
            entityId = i.toString(),
            metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
            source = "benchmark",
        )
    } }
    async.flush()
    async.close()

    // ── Case 5: async file sink throughput (enqueue + drain, no fsync) ──────────
    val fileDir = Files.createTempDirectory("ilogger-bench-file")
    val fileLogger = ILogger.create {
        sink(
            TextFileSink(
                file = fileDir.resolve("events.log"),
                flushEveryWrite = false,
                rotation = TextFileSink.Rotation(maxBytesPerFile = 1 shl 20, maxFiles = 4),
            ),
        )
        dispatch = DispatchMode.ASYNC
        bufferSize = 8_192
        batchSize = 1_024
    }
    val fileNs = Report.measure(warmup, iterations) {
        val start = System.nanoTime()
        for (i in 0 until n) {
            fileLogger.recordNow(
                action = "bench.op",
                level = LogLevel.AUDIT,
                entity = "Entity",
                entityId = i.toString(),
                metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
                source = "benchmark",
            )
        }
        fileLogger.flush()
        (System.nanoTime() - start) / n
    }
    fileLogger.close()
    Files.walk(fileDir).use { paths ->
        paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
    }

    // ── Case 6: record() audit through a hash-chained sink (G-36 stamping cost) ──
    // Compare with case 3 (same record shape straight to NopSink): the delta is
    // the canonical chain input + HMAC-SHA256 stamp + metadata copy + mutex.
    val chainedSink = HashChainSink(NopSink, SigningKey("bench", HmacTrailSigner(ByteArray(32))))
    val chained = ILogger.create {
        sink(chainedSink)
        dispatch = DispatchMode.INLINE
    }
    val chainedNs = Report.measure(warmup, iterations) { runLoop(n) {
        i ->
        chained.record(
            action = "bench.op",
            level = LogLevel.AUDIT,
            entity = "Entity",
            entityId = i.toString(),
            metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
            source = "benchmark",
        )
    } }
    chained.close()

    println()
    println("per-event cost (n=$n × $iterations iterations, after $warmup warmups):")
    println(Report.summarize("1. disabled logger (volatile check only)", disabledNs))
    println(Report.summarize("2. info() inline + nop sink", infoNs))
    println(Report.summarize("3. record() audit inline, 4 metadata", auditNs))
    println(Report.summarize("4. recordNow() async enqueue only", asyncNs))
    println(Report.summarize("5. async file sink, enqueue + drain", fileNs))
    println(Report.summarize("6. record() audit inline + hash chain", chainedNs))
}
