package io.ilogger.testing

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.EventIdGenerator
import io.ilogger.core.FixedClock
import io.ilogger.core.ILogger
import io.ilogger.core.ILoggerBuilder
import io.ilogger.core.LogEvent
import io.ilogger.core.LogMasking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.yield

/**
 * A ready-to-use, deterministic [ILogger] plus its [TestLogSink] for tests.
 *
 * Defaults: INLINE dispatch (no background workers, synchronous verification),
 * a fixed clock at epoch `1_700_000_000_000` and sequential event ids
 * (`e1`, `e2`, ...). Tests override what they exercise via [configure];
 * everything else stays deterministic.
 *
 * Pass [create]'s `workerDispatcher` a test dispatcher (for example
 * `StandardTestDispatcher(testScheduler)`) to drive async pipelines with
 * virtual time instead of real-time sleeps.
 */
public class TestKit(
    public val logger: ILogger,
    public val sink: TestLogSink,
    public val clock: FixedClock,
) {
    public companion object {
        private val DEFAULT_SENSITIVE_KEYS = listOf(
            "password",
            "passwd",
            "secret",
            "token",
            "credential",
            "authorization",
            "apikey",
            "cookie",
            "session",
            "email",
            "ssn",
            "cardnumber",
            "cvv",
        )

        public fun create(
            workerDispatcher: CoroutineDispatcher? = null,
            configure: ILoggerBuilder.() -> Unit = {},
        ): TestKit {
            val sink = TestLogSink()
            val clock = FixedClock(1_700_000_000_000)
            val logger = ILogger.create {
                sink(sink)
                this.clock = clock
                idGenerator = SequentialIdGenerator()
                dispatch = DispatchMode.INLINE
                if (workerDispatcher != null) this.workerDispatcher = workerDispatcher
                configure()
            }
            return TestKit(logger, sink, clock)
        }
    }

    /** Moves the fixed clock forward by [millis] without recording anything. */
    public fun advanceTimeBy(millis: Long) {
        clock.set(clock.nowMillis() + millis)
    }

    /**
     * Waits until every event admitted so far has reached the sink, then
     * flushes it. Returns `false` when [timeoutMillis] elapsed first.
     *
     * Yields once before the blocking barrier so queued worker coroutines get
     * a chance to run (relevant for test dispatchers and constrained pools).
     */
    public suspend fun drain(timeoutMillis: Long = 5_000L): Boolean {
        yield()
        return !logger.flush(timeoutMillis).timedOut
    }

    /**
     * Suspends until an event matching [predicate] is captured and returns it.
     * Throws [AssertionError] with the captured actions on timeout.
     */
    public suspend fun awaitEvent(
        timeoutMillis: Long = 5_000L,
        predicate: (LogEvent) -> Boolean,
    ): LogEvent {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            sink.events().firstOrNull(predicate)?.let { return it }
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "No event matched within ${timeoutMillis}ms. Captured actions: ${capturedActions()}",
                )
            }
            Thread.sleep(2)
        }
    }

    /** Captured events whose action equals [action], in arrival order. */
    public fun eventsFor(action: String): List<LogEvent> =
        sink.events().filter { it.action == action }

    /**
     * Asserts that at least one captured event matches every supplied filter,
     * or exactly [times] events when [times] is non-null. Returns the matches.
     */
    public fun assertLogged(
        action: String? = null,
        level: LogLevel? = null,
        times: Int? = null,
        predicate: (LogEvent) -> Boolean = { true },
    ): List<LogEvent> {
        val matches = sink.events().filter { event ->
            (action == null || event.action == action) &&
                (level == null || event.level == level) &&
                predicate(event)
        }
        val sizeOk = if (times == null) matches.isNotEmpty() else matches.size == times
        if (!sizeOk) {
            throw AssertionError(
                "Expected ${times ?: "at least one"} event(s) for " +
                    "action=${action ?: "*"} level=${level ?: "*"} but found ${matches.size}. " +
                    "Captured actions: ${capturedActions()}",
            )
        }
        return matches
    }

    /** Asserts that no captured event matches every supplied filter. */
    public fun assertNotLogged(
        action: String? = null,
        level: LogLevel? = null,
        predicate: (LogEvent) -> Boolean = { true },
    ) {
        val match = sink.events().firstOrNull { event ->
            (action == null || event.action == action) &&
                (level == null || event.level == level) &&
                predicate(event)
        }
        if (match != null) {
            throw AssertionError(
                "Unexpected event: ${golden(match)}",
            )
        }
    }

    /**
     * Asserts that no metadata key looks sensitive unless its value is masked
     * with [LogMasking.MASK] or hashed with [LogMasking.hash].
     *
     * With no [keys], checks common credential/PII key names (password, token,
     * secret, authorization, cookie, apiKey, email, ssn, card number, ...).
     * Pass explicit keys to check an application-specific vocabulary. Matching
     * normalizes case and separators, so `access_token` matches `token`.
     */
    public fun assertNoSensitive(vararg keys: String) {
        val watched = DEFAULT_SENSITIVE_KEYS.map(::normalizeKey) +
            keys.map(::normalizeKey).filter { it.isNotEmpty() }
        val leaks = ArrayList<String>()
        for (event in sink.events()) {
            inspectMap("", event.metadata, watched, leaks)
        }
        if (leaks.isNotEmpty()) {
            throw AssertionError(
                "Sensitive metadata leaked unmasked:\n" + leaks.joinToString("\n") { "  $it" },
            )
        }
    }

    /**
     * Canonical, deterministic text snapshot of [event]: schema, level,
     * outcome, identity, routing, sorted metadata, changes and failure tail.
     *
     * Snapshots stay stable across runs when the kit's fixed clock and
     * sequential ids are used; compare with `assertEquals(expected, kit.golden(event))`.
     */
    public fun golden(event: LogEvent): String = buildString {
        append("schema=").append(event.schemaId)
        append(" level=").append(event.level.name)
        append(" outcome=").append(event.outcome.name)
        append(" id=").append(event.id)
        append(" ts=").append(event.timestampMillis)
        event.actor?.let { actor ->
            append(" actor=").append(actor.type.name)
            actor.id?.let { append(':').append(it) }
            actor.name?.let { append('/').append(it) }
        }
        event.entity?.let { append(" entity=").append(it) }
        event.entityId?.let { append(" entityId=").append(it) }
        event.source?.let { append(" source=").append(it) }
        event.category?.let { append(" category=").append(it) }
        event.correlationId?.let { append(" corr=").append(it) }
        event.causationId?.let { append(" cause=").append(it) }
        if (event.metadata.isNotEmpty()) {
            append("\nmetadata:")
            for ((key, value) in event.metadata.toSortedMap()) {
                append("\n  ").append(key).append(" = ").append(value)
            }
        }
        if (event.changes.isNotEmpty()) {
            append("\nchanges:")
            for (change in event.changes) append("\n  ").append(change)
        }
        event.failure?.let { failure ->
            append("\nfailure: ").append(failure.errorType)
            failure.rootCauseType?.let { append(" root=").append(it) }
            failure.message?.let { append(" message=").append(it) }
            if (failure.cancelled) append(" cancelled")
            for (frame in failure.stackSummary) append("\n  at ").append(frame)
        }
    }

    private fun capturedActions(): String =
        sink.events().map { it.action }.distinct().sorted().joinToString(", ")

    private fun inspectMap(
        prefix: String,
        map: Map<*, *>,
        watched: List<String>,
        leaks: MutableList<String>,
    ) {
        for ((rawKey, value) in map) {
            val key = rawKey?.toString() ?: continue
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (watched.any { normalizeKey(key).contains(it) }) {
                if (!isProtected(value)) leaks += "$path = $value"
            } else {
                inspectValue(path, value, watched, leaks)
            }
        }
    }

    private fun inspectValue(
        path: String,
        value: Any?,
        watched: List<String>,
        leaks: MutableList<String>,
    ) {
        when (value) {
            is Map<*, *> -> inspectMap(path, value, watched, leaks)
            is Iterable<*> -> value.forEach { inspectValue(path, it, watched, leaks) }
        }
    }

    private fun isProtected(value: Any?): Boolean = when (value) {
        null -> true
        LogMasking.MASK -> true
        is String -> value.length == LogMasking.HASH_LENGTH &&
            value.all { it in '0'..'9' || it in 'a'..'f' }
        else -> false
    }

    private fun normalizeKey(key: String): String =
        key.lowercase().filter { it.isLetterOrDigit() }
}

/** Event ids `e1`, `e2`, ... — readable in test assertions and diff output. */
public class SequentialIdGenerator : EventIdGenerator {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    override fun next(): String = "e${counter.incrementAndGet()}"
}
