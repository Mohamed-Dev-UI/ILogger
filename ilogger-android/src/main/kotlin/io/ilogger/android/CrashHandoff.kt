package io.ilogger.android

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogClock
import io.ilogger.core.LogEvent
import io.ilogger.core.LogFailure
import io.ilogger.core.SystemClock
import io.ilogger.core.sink.EventCodec
import io.ilogger.serialization.JsonEventCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * An [Thread.UncaughtExceptionHandler] that persists the [CrashBuffer] and a
 * synthetic crash event before the process dies.
 *
 * Why a separate file: the normal dispatch pipeline is asynchronous and the
 * process is killed immediately after an uncaught exception, so events still
 * queued in memory would be lost. The handler writes with `FileOutputStream`
 * plus `fd.sync()` on the crashing thread, then delegates to the previous
 * handler so platform crash reporting still runs.
 *
 * Files are JSONL (one [JsonEventCodec] object per line) named
 * `crash-<timestampMillis>.jsonl`. Call [pending] on the next launch to show or
 * ship them, and [drain] to read and delete in one step.
 *
 * The handoff is installed process-wide and replaces the previous default
 * handler; [uninstall] restores it. The previous handler is captured at
 * [install] time, not construction.
 *
 * @param buffer source of recently dispatched events.
 * @param directory destination directory; created on the first crash.
 * @param delegate handler invoked after the report is written. Defaults to the
 *   handler that was installed at [install] time; pass one explicitly for
 *   tests or custom chains.
 */
public class CrashHandoff(
    private val buffer: CrashBuffer,
    private val directory: File,
    private val codec: EventCodec = JsonEventCodec,
    private val clock: LogClock = SystemClock,
    private val onError: (Throwable) -> Unit = {},
    private val delegate: Thread.UncaughtExceptionHandler? = null,
) : Thread.UncaughtExceptionHandler {

    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = delegate

    /** Installs as the process default handler and returns this instance. */
    public fun install(): CrashHandoff {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        return this
    }

    /** Restores the handler captured by [install], when this handler is still installed. */
    public fun uninstall() {
        if (Thread.getDefaultUncaughtExceptionHandler() === this) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        previous = null
    }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching { writeReport(thread, error) }
            .onFailure { reportError -> runCatching { onError(reportError) } }

        val target = previous
        if (target != null && target !== this) {
            target.uncaughtException(thread, error)
        } else {
            error.printStackTrace()
        }
    }

    /** Crash reports on disk, oldest first, without deleting them. */
    public fun pending(): List<CrashReport> = reportFiles().map { file ->
        CrashReport(file, runCatching { file.readLines() }.getOrElse { emptyList() })
    }

    /** Reads and deletes all pending reports. */
    public fun drain(): List<CrashReport> {
        val reports = pending()
        reports.forEach { report -> runCatching { report.file.delete() } }
        return reports
    }

    private fun reportFiles(): List<File> {
        val files = directory.listFiles() ?: return emptyList()
        return files
            .filter { file -> file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) }
            .sortedBy { file -> file.name }
    }

    private fun writeReport(thread: Thread, error: Throwable) {
        directory.mkdirs()
        val file = File(directory, "$FILE_PREFIX${clock.nowMillis()}$FILE_SUFFIX")
        val events = buffer.snapshot() + crashEvent(thread, error)
        FileOutputStream(file).use { output ->
            val writer = output.bufferedWriter(StandardCharsets.UTF_8)
            events.forEach { event ->
                writer.write(codec.encode(event))
                writer.write("\n")
            }
            writer.flush()
            // The process is about to die: force the bytes to the device.
            output.fd.sync()
            writer.close()
        }
    }

    private fun crashEvent(thread: Thread, error: Throwable): LogEvent = LogEvent(
        id = UUID.randomUUID().toString(),
        timestampMillis = clock.nowMillis(),
        level = LogLevel.ERROR,
        action = CRASH_ACTION,
        entity = "Process",
        source = CRASH_SOURCE,
        metadata = mapOf("thread" to thread.name),
        failure = LogFailure.from(error),
    )

    public companion object {
        public const val CRASH_ACTION: String = "android.crash"
        public const val CRASH_SOURCE: String = "io.ilogger.android.CrashHandoff"
        public const val FILE_PREFIX: String = "crash-"
        public const val FILE_SUFFIX: String = ".jsonl"
    }
}

/** One persisted crash report: the file plus its JSONL lines. */
public class CrashReport(
    public val file: File,
    public val lines: List<String>,
)
