package benchmark

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.DirectLogSink
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.core.sink.TextFileSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
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

/** Direct sink variant: lets inline diagnostics skip the coroutine bridge. */
object NopDirectSink : DirectLogSink {
    override suspend fun write(event: LogEvent) = Unit
    override fun writeDirect(event: LogEvent) = Unit
}

/**
 * JMH suite measuring the engine paths that matter to producers:
 *
 * 1. disabled logger — the application-side kill switch
 * 2. filtered diagnostics — min-level gate
 * 3. info() inline + direct nop sink
 * 4. suspend record() audit inline (runBlocking bridge is uniform across cases)
 * 5. recordNow() async enqueue
 * 6. recordNow() buffered enqueue (batch accumulation, sink excluded)
 * 7. recordNow() fanned out to two per-sink pipelines
 * 8. generated wrapper dispatch, no metadata
 * 9. generated wrapper dispatch + compile-time metadata extraction
 * 10. INLINE audit into a rotating file sink (full write path)
 *
 * Budget-gated cases (1, 5, 6) are mirrored by `BudgetGateTest`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 7, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class EnginePathsBenchmarks {

    private lateinit var disabled: ILogger
    private lateinit var filtered: ILogger
    private lateinit var inline: ILogger
    private lateinit var async: ILogger
    private lateinit var buffered: ILogger
    private lateinit var fanOut: ILogger
    private lateinit var wrapper: BenchRepository
    private lateinit var wrapperItem: BenchItem
    private lateinit var fileDir: Path
    private lateinit var file: ILogger

    @Setup
    fun setup() {
        disabled = ILogger.create {
            sink(NopDirectSink)
            enabled = false
        }
        filtered = ILogger.create {
            sink(NopDirectSink)
            minLevel = LogLevel.WARN
        }
        inline = ILogger.create {
            sink(NopDirectSink)
            dispatch = DispatchMode.INLINE
        }
        async = ILogger.create {
            sink(NopSink)
            dispatch = DispatchMode.ASYNC
            bufferSize = 8_192
            batchSize = 1_024
        }
        buffered = ILogger.create {
            sink(NopSink)
            dispatch = DispatchMode.BUFFERED
            bufferSize = 8_192
            batchSize = 1_024
            flushIntervalMillis = 60_000
        }
        fanOut = ILogger.create {
            sink(NopSink)
            sink(NopSink)
            dispatch = DispatchMode.ASYNC
            bufferSize = 8_192
            batchSize = 1_024
        }
        wrapper = InMemoryBenchRepository().logged(inline)
        wrapperItem = BenchItem(id = 42, label = "benchmark item")

        fileDir = Files.createTempDirectory("ilogger-benchmark")
        file = ILogger.create {
            sink(
                TextFileSink(
                    file = fileDir.resolve("events.log"),
                    flushEveryWrite = false,
                    rotation = TextFileSink.Rotation(maxBytesPerFile = 1 shl 20, maxFiles = 4),
                ),
            )
            dispatch = DispatchMode.INLINE
        }
    }

    @TearDown
    fun teardown() {
        async.flush()
        buffered.flush()
        fanOut.flush()
        listOf(async, buffered, fanOut, file, inline, filtered, disabled).forEach { it.close() }
        Files.walk(fileDir).use { paths ->
            paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Benchmark
    fun disabledVolatileCheck() {
        disabled.info("benchmark message")
    }

    @Benchmark
    fun filteredDiagnostic() {
        filtered.info("benchmark message")
    }

    @Benchmark
    fun infoInlineNopSink() {
        inline.info("benchmark message")
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

    @Benchmark
    fun recordNowBufferedEnqueue() {
        buffered.recordNow(
            action = "bench.op",
            level = LogLevel.AUDIT,
            entity = "Entity",
            entityId = "42",
            metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
            source = "benchmark",
            dispatch = DispatchMode.BUFFERED,
        )
    }

    @Benchmark
    fun recordNowFanOutAsync() {
        fanOut.recordNow(
            action = "bench.op",
            level = LogLevel.AUDIT,
            entity = "Entity",
            entityId = "42",
            metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
            source = "benchmark",
        )
    }

    @Benchmark
    fun wrapperDispatch(): Int = runBlocking {
        wrapper.touch(42)
        1
    }

    @Benchmark
    fun wrapperMetadataExtraction(): Int = runBlocking {
        wrapper.save(wrapperItem)
        1
    }

    @Benchmark
    fun fileSinkInline() {
        file.recordBlocking(
            action = "bench.op",
            level = LogLevel.AUDIT,
            entity = "Entity",
            entityId = "42",
            metadata = mapOf("a" to 1, "b" to "x", "c" to false, "d" to null),
            source = "benchmark",
            dispatch = DispatchMode.INLINE,
        )
    }
}
