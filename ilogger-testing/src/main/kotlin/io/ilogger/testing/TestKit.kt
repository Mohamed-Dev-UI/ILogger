package io.ilogger.testing

import io.ilogger.annotations.DispatchMode
import io.ilogger.core.EventIdGenerator
import io.ilogger.core.FixedClock
import io.ilogger.core.ILogger
import io.ilogger.core.ILoggerBuilder

/**
 * A ready-to-use, deterministic [ILogger] plus its [TestLogSink] for tests.
 *
 * Defaults: INLINE dispatch (no background workers, synchronous verification),
 * a fixed clock at epoch `1_700_000_000_000` and sequential event ids
 * (`e1`, `e2`, ...). Tests override what they exercise via [configure];
 * everything else stays deterministic.
 */
public class TestKit(
    public val logger: ILogger,
    public val sink: TestLogSink,
    public val clock: FixedClock,
) {
    public companion object {
        public fun create(configure: ILoggerBuilder.() -> Unit = {}): TestKit {
            val sink = TestLogSink()
            val clock = FixedClock(1_700_000_000_000)
            val logger = ILogger.create {
                sink(sink)
                this.clock = clock
                idGenerator = SequentialIdGenerator()
                dispatch = DispatchMode.INLINE
                configure()
            }
            return TestKit(logger, sink, clock)
        }
    }
}

/** Event ids `e1`, `e2`, ... — readable in test assertions and diff output. */
public class SequentialIdGenerator : EventIdGenerator {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    override fun next(): String = "e${counter.incrementAndGet()}"
}
