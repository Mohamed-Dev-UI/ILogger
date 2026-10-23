package io.ilogger.android

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.sink.EventCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatSinkTest {

    private data class Line(val priority: Int, val tag: String, val text: String)

    @Test
    fun `maps every level to a logcat priority`() {
        assertEquals(LogcatSink.PRIORITY_VERBOSE, LogcatSink.priorityOf(LogLevel.TRACE))
        assertEquals(LogcatSink.PRIORITY_DEBUG, LogcatSink.priorityOf(LogLevel.DEBUG))
        assertEquals(LogcatSink.PRIORITY_INFO, LogcatSink.priorityOf(LogLevel.INFO))
        assertEquals(LogcatSink.PRIORITY_WARN, LogcatSink.priorityOf(LogLevel.WARN))
        assertEquals(LogcatSink.PRIORITY_ERROR, LogcatSink.priorityOf(LogLevel.ERROR))
        assertEquals(LogcatSink.PRIORITY_INFO, LogcatSink.priorityOf(LogLevel.AUDIT))
    }

    @Test
    fun `writes the encoded event with tag and priority`() {
        val lines = mutableListOf<Line>()
        val sink = LogcatSink(
            tag = "DemoTag",
            writeLine = { priority, tag, text -> lines += Line(priority, tag, text) },
        )

        sink.writeDirect(event(id = "e1", level = LogLevel.WARN, action = "task.created"))

        assertEquals(1, lines.size)
        assertEquals(LogcatSink.PRIORITY_WARN, lines[0].priority)
        assertEquals("DemoTag", lines[0].tag)
        assertTrue(lines[0].text.contains("task.created"))
    }

    @Test
    fun `supports a per-event tag mapping`() {
        val tags = mutableListOf<String>()
        val sink = LogcatSink(
            tagFor = { event -> event.category ?: "fallback" },
            writeLine = { _, tag, _ -> tags += tag },
        )

        sink.writeDirect(event(action = "a.b", category = "audit"))
        sink.writeDirect(event(action = "a.c", category = null))

        assertEquals(listOf("audit", "fallback"), tags)
    }

    @Test
    fun `splits long lines instead of losing them to the logcat limit`() {
        val chunks = mutableListOf<String>()
        val sink = LogcatSink(
            codec = EventCodec { "0123456789" },
            maxChunkLength = 4,
            writeLine = { _, _, text -> chunks += text },
        )

        sink.writeDirect(event())

        assertEquals(listOf("0123", "4567", "89"), chunks)
    }

    private fun event(
        id: String = "e1",
        level: LogLevel = LogLevel.AUDIT,
        action: String = "android.test",
        category: String? = null,
    ): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1_000L,
        level = level,
        action = action,
        category = category,
    )
}
