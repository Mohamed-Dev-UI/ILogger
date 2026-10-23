package io.ilogger.android

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogClock
import io.ilogger.core.LogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashHandoffTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `writes the buffered events and the crash event, then delegates`() {
        val buffer = CrashBuffer(capacity = 2)
        buffer.writeDirect(event("e1"))
        buffer.writeDirect(event("e2"))
        buffer.writeDirect(event("e3"))
        val delegate = RecordingHandler()

        val handoff = CrashHandoff(
            buffer = buffer,
            directory = temporaryFolder.newFolder("crash"),
            clock = LogClock { 1_234L },
            delegate = delegate,
        )

        handoff.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertSame(Thread.currentThread(), delegate.thread)
        assertTrue(delegate.error is IllegalStateException)

        val report = handoff.pending().single()
        assertEquals(3, report.lines.size)
        assertTrue(report.lines[0].contains(Regex("\"id\":\"e2\"")))
        assertTrue(report.lines[1].contains(Regex("\"id\":\"e3\"")))
        assertTrue(report.lines[2].contains(Regex("\"action\":\"android.crash\"")))
        assertTrue(report.lines[2].contains(Regex("\"errorType\":\"java.lang.IllegalStateException\"")))
        assertTrue(report.file.name.contains("1234"))
    }

    @Test
    fun `drain returns reports and removes them`() {
        val handoff = CrashHandoff(
            buffer = CrashBuffer(),
            directory = temporaryFolder.newFolder("drain"),
            delegate = RecordingHandler(),
        )
        handoff.uncaughtException(Thread.currentThread(), RuntimeException("one"))
        handoff.uncaughtException(Thread.currentThread(), RuntimeException("two"))

        assertEquals(2, handoff.pending().size)
        val drained = handoff.drain()

        assertEquals(2, drained.size)
        assertTrue(handoff.pending().isEmpty())
        assertFalse(drained.first().file.exists())
    }

    @Test
    fun `a write failure is reported and the delegate still runs`() {
        val blockingFile = File(temporaryFolder.root, "not-a-directory")
        blockingFile.writeText("file")
        var reported: Throwable? = null
        val delegate = RecordingHandler()
        val handoff = CrashHandoff(
            buffer = CrashBuffer(),
            directory = File(blockingFile, "crash"),
            onError = { error -> reported = error },
            delegate = delegate,
        )

        handoff.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertTrue(reported != null)
        assertSame(Thread.currentThread(), delegate.thread)
        assertTrue(handoff.pending().isEmpty())
    }

    @Test
    fun `install replaces the default handler and uninstall restores it`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val handoff = CrashHandoff(
            buffer = CrashBuffer(),
            directory = temporaryFolder.newFolder("install"),
        )
        try {
            handoff.install()

            assertSame(handoff, Thread.getDefaultUncaughtExceptionHandler())

            handoff.uninstall()

            assertSame(original, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var thread: Thread? = null
        var error: Throwable? = null

        override fun uncaughtException(t: Thread, e: Throwable) {
            thread = t
            error = e
        }
    }

    private fun event(id: String, level: LogLevel = LogLevel.AUDIT): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1L,
        level = level,
        action = "android.test",
    )
}
