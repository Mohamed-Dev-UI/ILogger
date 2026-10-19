package io.ilogger.core.sink

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Appends newline-delimited encoded events to a local file.
 *
 * ## Guarantees
 *
 * - Survives process death when the OS writes buffers ([flushEveryWrite]).
 * - No network behavior whatsoever.
 * - Optional size-based rotation: files roll to `<name>.1`, `<name>.2`, …
 *   (newest first), deleting beyond [Rotation.maxFiles].
 * - Single-process writer; concurrent processes on one file are undefined.
 * - IOExceptions propagate to the dispatch engine's [ErrorMode][io.ilogger.core.ErrorMode].
 *
 * @param file target file path (created with parent directories).
 * @param codec text codec; use JSON codecs from `ilogger-serialization` for machine consumption.
 * @param append open in append mode (true) or truncate (false).
 * @param flushEveryWrite flush the OS buffer after each event (durability) or
 *   batch internally until [flush] (throughput).
 * @param rotation optional size-based rotation policy.
 */
public class TextFileSink(
    file: Path,
    private val codec: EventCodec = DefaultTextEventCodec,
    append: Boolean = true,
    private val flushEveryWrite: Boolean = true,
    private val rotation: Rotation? = null,
) : LogSink {

    /** Size-based rotation policy. */
    public class Rotation(
        public val maxBytesPerFile: Long,
        public val maxFiles: Int,
    ) {
        init {
            require(maxBytesPerFile > 0) { "maxBytesPerFile must be positive" }
            require(maxFiles >= 0) { "maxFiles must be >= 0" }
        }
    }

    private val lock = Any()
    private val file: Path
    private var writer: BufferedWriter?
    private var writtenBytes: Long = 0

    init {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        this.file = file
        this.writer = open(file, append)
    }

    private fun open(file: Path, append: Boolean): BufferedWriter {
        val options = if (append) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        writer?.let { try { it.close() } catch (_: Throwable) {} }
        val channel = Files.newByteChannel(file, *options)
        writtenBytes = if (append) channel.size() else 0
        return BufferedWriter(java.nio.channels.Channels.newWriter(channel, StandardCharsets.UTF_8), 16 * 1024)
    }

    override suspend fun write(event: LogEvent) {
        val line = codec.encode(event) + "\n"
        val bytes = line.toByteArray(StandardCharsets.UTF_8).size.toLong()
        synchronized(lock) {
            if (rotation != null && writer != null && writtenBytes + bytes > rotation.maxBytesPerFile) roll()
            receiver()?.write(line)
            writtenBytes += bytes
            if (flushEveryWrite) receiver()?.flush()
        }
    }

    override suspend fun flush() {
        synchronized(lock) { receiver()?.flush() }
    }

    /** Rolls to the next file (callers hold [lock]). */
    private fun roll() {
        val maxFiles = rotation!!.maxFiles
        if (maxFiles > 0) {
            for (index in maxFiles - 1 downTo 1) {
                val from = file.resolveSibling("$file.$index")
                if (Files.exists(from)) {
                    val to = file.resolveSibling("$file.${index + 1}")
                    if (index + 1 > maxFiles) Files.deleteIfExists(from) else Files.move(from, to)
                }
            }
            Files.move(file, file.resolveSibling("$file.1"))
        } else {
            Files.deleteIfExists(file)
        }
        writer = open(file, append = false)
    }

    private fun receiver(): BufferedWriter {
        val current = writer
        checkNotNull(current) { "TextFileSink is closed" }
        return current
    }

    override fun close() {
        synchronized(lock) {
            writer?.flush()
            try { writer?.close() } catch (_: Throwable) {}
            writer = null
        }
    }
}
