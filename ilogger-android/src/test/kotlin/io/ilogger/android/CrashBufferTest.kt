package io.ilogger.android

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashBufferTest {

    @Test
    fun `keeps the newest events up to capacity`() {
        val buffer = CrashBuffer(capacity = 2)

        buffer.writeDirect(event("e1"))
        buffer.writeDirect(event("e2"))
        buffer.writeDirect(event("e3"))

        assertEquals(2, buffer.size)
        assertEquals(listOf("e2", "e3"), buffer.snapshot().map { it.id })
    }

    @Test
    fun `snapshot is a stable copy`() {
        val buffer = CrashBuffer(capacity = 4)
        buffer.writeDirect(event("e1"))

        val snapshot = buffer.snapshot()
        buffer.writeDirect(event("e2"))

        assertEquals(1, snapshot.size)
        assertEquals(2, buffer.size)
    }

    @Test
    fun `clear empties the ring`() {
        val buffer = CrashBuffer(capacity = 4)
        buffer.writeDirect(event("e1"))

        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0, buffer.size)
    }

    private fun event(id: String): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1L,
        level = LogLevel.AUDIT,
        action = "android.test",
    )
}
