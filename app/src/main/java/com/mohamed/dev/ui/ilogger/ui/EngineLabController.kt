package com.mohamed.dev.ui.ilogger.ui

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.OverflowPolicy
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.system.measureTimeMillis
import javax.inject.Singleton

enum class LabScenario(val title: String, val blurb: String) {
    BACKPRESSURE(
        "Bounded buffer + backpressure",
        "80 events pushed into a 16-slot queue with a slow sink and DROP_OLDEST.",
    ),
    RETRY(
        "Sink failure → retry",
        "A sink fails its first two writes; AUDIT events retry with backoff.",
    ),
    SAMPLING(
        "Diagnostic sampling",
        "TRACE at rate 0.0 is suppressed deterministically; DEBUG passes.",
    ),
    INLINE(
        "INLINE is synchronous",
        "With INLINE dispatch the sink already holds the event when record() returns.",
    ),
    MIN_LEVEL(
        "minLevel only gates diagnostics",
        "minLevel = WARN hides debug/info but never filters an audit record().",
    ),
    BLOCK(
        "BLOCK is backpressure, not loss",
        "40 events into an 8-slot queue with a deliberately slow sink: producers wait, nothing is lost.",
    ),
    PROPAGATE(
        "PROPAGATE fails the caller",
        "INLINE + PROPAGATE is the one mode where a broken sink can fail the audited operation.",
    ),
    DISABLED(
        "The runtime off-switch",
        "enabled = false skips all work; toggling it back resumes without rebuilding the logger.",
    ),
}

data class LabResult(
    val scenario: LabScenario,
    val headline: String,
    val details: List<String>,
)

/** Independent loggers per experiment: the application's logger is never mutated. */
@Singleton
class EngineLabController @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _results = MutableStateFlow<Map<LabScenario, LabResult>>(emptyMap())
    val results: StateFlow<Map<LabScenario, LabResult>> = _results.asStateFlow()

    private val _running = MutableStateFlow<LabScenario?>(null)
    val running: StateFlow<LabScenario?> = _running.asStateFlow()

    fun run(scenario: LabScenario) {
        if (_running.value != null) return
        scope.launch {
            _running.value = scenario
            try {
                val result = when (scenario) {
                    LabScenario.BACKPRESSURE -> backpressure()
                    LabScenario.RETRY -> retry()
                    LabScenario.SAMPLING -> sampling()
                    LabScenario.INLINE -> inline()
                    LabScenario.MIN_LEVEL -> minLevel()
                    LabScenario.BLOCK -> block()
                    LabScenario.PROPAGATE -> propagate()
                    LabScenario.DISABLED -> disabled()
                }
                _results.value = _results.value + (scenario to result)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // A scenario is a self-contained experiment; a configuration
                // mistake must never take down the host application.
                _results.value = _results.value + (
                    scenario to LabResult(
                        scenario = scenario,
                        headline = "Scenario failed: ${error::class.java.simpleName}",
                        details = listOf(error.message ?: "No message was provided."),
                    )
                )
            } finally {
                _running.value = null
            }
        }
    }

    private suspend fun backpressure(): LabResult {
        val delivered = AtomicInteger()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 16
            batchSize = 4
            overflow = OverflowPolicy.DROP_OLDEST
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) {
                        delay(5)
                        delivered.incrementAndGet()
                    }
                },
            )
        }
        try {
            repeat(80) { index ->
                logger.info("lab.flood", "index" to index)
            }
            logger.flush()
        } finally {
            logger.close()
        }
        return LabResult(
            scenario = LabScenario.BACKPRESSURE,
            headline = "Delivered ${delivered.get()} of 80 events",
            details = listOf(
                "The queue never grew past 16 entries: memory is bounded by design.",
                "DROP_OLDEST keeps the newest events; the old ones are aged out before sinks.",
                "For zero loss use OverflowPolicy.BLOCK (producer slows) or FAIL (explicit error).",
            ),
        )
    }

    private suspend fun retry(): LabResult {
        val attempts = AtomicInteger()
        val delivered = AtomicInteger()
        val sink = object : LogSink {
            override suspend fun write(event: LogEvent) {
                if (attempts.incrementAndGet() <= 2) {
                    throw IllegalStateException("transient sink failure")
                }
                delivered.incrementAndGet()
            }
        }
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            retryMaxAttempts = 3
            retryBaseBackoffMillis = 10
            sink(sink)
            onErrorMode(LogLevel.AUDIT, io.ilogger.core.ErrorMode.RETRY)
        }
        try {
            logger.record(
                action = "lab.retry",
                entity = "Lab",
                entityId = "retry-1",
                source = "EngineLab",
            )
            logger.flush()
        } finally {
            logger.close()
        }
        return LabResult(
            scenario = LabScenario.RETRY,
            headline = "Delivered ${delivered.get()} event after ${attempts.get()} sink attempt(s)",
            details = listOf(
                "AUDIT defaults to ErrorMode.RETRY; WARN/INFO/DEBUG default to DROP.",
                "Backoff is bounded exponential: 10ms, 20ms, 40ms … (cap 10s per wait).",
                "PROPAGATE only makes sense with INLINE; the async worker downgrades it.",
            ),
        )
    }

    private suspend fun sampling(): LabResult {
        val received = AtomicInteger()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            sampling(LogLevel.TRACE to 0.0)
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) {
                        received.incrementAndGet()
                    }
                },
            )
        }
        try {
            repeat(3) { logger.trace("lab.trace", "n" to it) }
            logger.debug("lab.debug")
            logger.flush()
        } finally {
            logger.close()
        }
        return LabResult(
            scenario = LabScenario.SAMPLING,
            headline = "Received ${received.get()} of 4 diagnostics",
            details = listOf(
                "Rate 0.0 is deterministic: perfect for silencing a level in tests.",
                "Sampling runs before event allocation, so a declined event costs zero.",
                "AUDIT events are never sampled; @Log(sampling < 1.0) on AUDIT is a compile error.",
            ),
        )
    }

    private suspend fun inline(): LabResult {
        val received = AtomicInteger()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) {
                        received.incrementAndGet()
                    }
                },
            )
        }
        val deliveredOnReturn = try {
            logger.record(action = "lab.inline", entity = "Lab", entityId = "inline-1")
            received.get()
        } finally {
            logger.close()
        }
        return LabResult(
            scenario = LabScenario.INLINE,
            headline = "Sink count at return = $deliveredOnReturn",
            details = listOf(
                "INLINE pays sink latency on the caller's path — use it only when durability must precede return.",
                "It also makes ErrorMode.PROPAGATE meaningful: a sink failure can fail the operation.",
                "The application logger uses BUFFERED instead: one enqueue per event, real batches to sinks.",
            ),
        )
    }

    private suspend fun minLevel(): LabResult {
        val received = AtomicInteger()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            minLevel = LogLevel.WARN
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) {
                        received.incrementAndGet()
                    }
                },
            )
        }
        try {
            logger.debug("hidden")
            logger.info("hidden")
            logger.warn("visible")
            logger.error("visible")
            logger.record(
                action = "lab.audit",
                entity = "Lab",
                entityId = "audit-never-filtered",
                source = "EngineLab",
            )
        } finally {
            logger.close()
        }
        return LabResult(
            scenario = LabScenario.MIN_LEVEL,
            headline = "Received ${received.get()} of 5 calls",
            details = listOf(
                "minLevel is a diagnostics knob: warn + error pass, debug + info do not.",
                "record()/recordNow() are gated by enabled only — audit traffic is never silently filtered.",
                "Use enabled = false (or ILogger.disabled) to opt out of auditing explicitly.",
            ),
        )
    }

    private suspend fun block(): LabResult {
        val delivered = AtomicInteger()
        val logger = ILogger.create {
            dispatch = DispatchMode.ASYNC
            bufferSize = 8
            batchSize = 4
            overflow = OverflowPolicy.BLOCK
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) {
                        delay(4)
                        delivered.incrementAndGet()
                    }
                },
            )
        }
        val elapsed = try {
            measureTimeMillis {
                repeat(40) { index -> logger.info("lab.block", "index" to index) }
                logger.flush()
            }
        } finally {
            logger.close()
        }
        return LabResult(
            scenario = LabScenario.BLOCK,
            headline = "Delivered ${delivered.get()} of 40 events in ${elapsed}ms",
            details = listOf(
                "BLOCK suspends the producer when the queue is full instead of dropping.",
                "Zero loss is never free: the caller absorbs the sink's latency.",
                "Never BLOCK on the UI thread; this run took roughly 40 × the sink's 4ms write.",
            ),
        )
    }

    private suspend fun propagate(): LabResult {
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onErrorMode(LogLevel.AUDIT, io.ilogger.core.ErrorMode.PROPAGATE)
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) {
                        throw IllegalStateException("sink is unavailable")
                    }
                },
            )
        }
        val thrown = try {
            runCatching {
                logger.record(
                    action = "lab.propagate",
                    entity = "Lab",
                    entityId = "propagate-1",
                    source = "EngineLab",
                )
            }.exceptionOrNull()
        } finally {
            logger.close()
        }
        return LabResult(
            scenario = LabScenario.PROPAGATE,
            headline = if (thrown == null) {
                "record() returned normally"
            } else {
                "record() threw ${thrown::class.java.simpleName}"
            },
            details = listOf(
                "PROPAGATE is only meaningful with INLINE: the caller is still there to see the failure.",
                "The async worker downgrades PROPAGATE to report + drop — logging must not break the app.",
                "Reserve it for paths where missing the audit is worse than failing the operation.",
            ),
        )
    }

    private suspend fun disabled(): LabResult {
        val received = AtomicInteger()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) {
                        received.incrementAndGet()
                    }
                },
            )
        }
        try {
            logger.record(action = "lab.enabled", entity = "Lab", entityId = "1", source = "EngineLab")
            logger.enabled = false
            logger.record(action = "lab.disabled", entity = "Lab", entityId = "2", source = "EngineLab")
            logger.info("while disabled")
            logger.enabled = true
            logger.record(action = "lab.re-enabled", entity = "Lab", entityId = "3", source = "EngineLab")
        } finally {
            logger.close()
        }
        return LabResult(
            scenario = LabScenario.DISABLED,
            headline = "Delivered ${received.get()} of 4 calls around a disabled window",
            details = listOf(
                "enabled is the cheapest runtime switch: every entry point returns before allocation.",
                "ILogger.disabled is the permanent opt-out for compile-time wiring and tests.",
                "minLevel is not a mute button: it only filters diagnostics, never audit records.",
            ),
        )
    }
}
