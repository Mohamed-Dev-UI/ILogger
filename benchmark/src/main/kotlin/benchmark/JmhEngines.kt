package benchmark

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.ILogger
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * JMH suite measuring the four engine paths (same cases as the deterministic
 * harness in [MainKt], so numbers are directly comparable):
 *
 * 1. disabled logger — the application-side kill switch
 * 2. info() inline dispatch + nop sink
 * 3. suspend record() audit inline dispatch (runBlocking overhead is uniform
 *    for audits and costs the same between cases)
 * 4. recordNow() async enqueue only
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 7, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class EnginePathsBenchmarks {

    private lateinit var disabled: ILogger
    private lateinit var inline: ILogger
    private lateinit var async: ILogger

    @Setup
    fun setup() {
        disabled = ILogger.create {
            sink(NopSink)
            enabled = false
        }
        inline = ILogger.create {
            sink(NopSink)
            dispatch = DispatchMode.INLINE
        }
        async = ILogger.create {
            sink(NopSink)
            dispatch = DispatchMode.ASYNC
            batchSize = 4096
            flushIntervalMillis = 5_000
        }
    }

    @TearDown
    fun teardown() {
        async.flush()
        async.close()
    }

    @Benchmark
    fun disabledVolatileCheck() {
        disabled.info("benchmark message t")
    }

    @Benchmark
    fun infoInlineNopSink() {
        inline.info("benchmark message t")
    }

    @Benchmark
    fun recordAuditInline(): Int = runBlocking {
        inline.record(
            action = "bench.op",
            level = LogLevel.AUDIT,
            entity = "Entity",
            entityId = "42",
            metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
            source = "benchmark",
        )
        1
    }

    @Benchmark
    fun recordNowAsyncEnqueue() {
        async.recordNow(
            action = "bench.op",
            level = LogLevel.AUDIT,
            entity = "Entity",
            entityId = "42",
            metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
            source = "benchmark",
        )
    }
}
