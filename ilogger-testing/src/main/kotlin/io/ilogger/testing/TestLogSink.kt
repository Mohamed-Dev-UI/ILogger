package io.ilogger.testing

/**
 * In-memory [LogSink][io.ilogger.core.LogSink] used by [TestKit].
 *
 * Behaviour lives in [InMemoryLogSink]: thread-safe capture, `events()`,
 * `last()`, `count()`, `clear()` and `awaitCount()`. This name stays as the
 * stable TestKit-facing type.
 */
public class TestLogSink : InMemoryLogSink()
