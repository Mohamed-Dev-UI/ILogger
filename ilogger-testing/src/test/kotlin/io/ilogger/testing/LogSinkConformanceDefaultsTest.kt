package io.ilogger.testing

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink

/**
 * Exercises the conformance suite's optional hooks with their defaults: a sink
 * that is not a [io.ilogger.core.DirectLogSink] and no content read-back.
 */
class LogSinkConformanceDefaultsTest : LogSinkConformance() {

    private class PlainSink : LogSink {
        private val recorded = ArrayList<LogEvent>()

        override suspend fun write(event: LogEvent) {
            synchronized(recorded) { recorded.add(event) }
        }

        override suspend fun writeAll(events: List<LogEvent>) {
            synchronized(recorded) { recorded.addAll(events) }
        }

        override suspend fun flush() = Unit

        override fun close() = Unit

        fun ids(): List<String> = synchronized(recorded) { recorded.map { it.id } }
    }

    override fun createSink(): LogSink = PlainSink()

    override fun deliveredIds(sink: LogSink): List<String> = (sink as PlainSink).ids()
}
