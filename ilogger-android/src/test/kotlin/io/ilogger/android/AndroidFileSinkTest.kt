package io.ilogger.android

import io.ilogger.annotations.LogLevel
import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidFileSinkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `writes JSONL without exposing a direct write path`() = runTest {
        val file = temporaryFolder.newFile("audit.jsonl")
        val sink = AndroidFileSink.create(file)

        assertFalse(DirectLogSink::class.java.isAssignableFrom(sink.javaClass))
        assertEquals(file.absolutePath, sink.path)

        sink.write(event("e1"))
        sink.writeAll(listOf(event("e2"), event("e3")))
        sink.flush()
        sink.close()

        val lines = file.readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"id\":\"e1\""))
        assertTrue(lines[2].contains("\"id\":\"e3\""))
    }

    @Test
    fun `empty batches do not touch the file`() = runTest {
        val file = temporaryFolder.newFile("empty.jsonl")
        val sink = AndroidFileSink.create(file)

        sink.writeAll(emptyList())
        sink.flush()
        sink.close()

        assertEquals(0, file.length())
    }

    private fun event(id: String): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1L,
        level = LogLevel.AUDIT,
        action = "android.test",
    )
}
