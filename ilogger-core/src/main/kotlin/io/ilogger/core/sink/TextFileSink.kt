package io.ilogger.core.sink

import io.ilogger.core.DirectLogSink
import io.ilogger.core.LogClock
import io.ilogger.core.LogEvent
import io.ilogger.core.SystemClock
import io.ilogger.core.crypto.TextCipher
import java.io.BufferedWriter
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Appends newline-delimited encoded events to a local file.
 *
 * ## Guarantees
 *
 * - Three durability levels, chosen by constructor flags:
 *   **BUFFERED** ([flushEveryWrite] = `false`) — fastest, buffer lost on crash;
 *   **OS** ([flushEveryWrite] = `true`) — events reach the OS page cache;
 *   **FSYNC** ([fsyncEveryWrite] = `true`) — events are forced to the storage
 *   device on every write.
 * - No network behavior whatsoever.
 * - Optional size-based rotation: files roll to `<name>.1`, `<name>.2`, …
 *   (newest first), deleting beyond [Rotation.maxFiles]. Every roll count,
 *   including `0`, `1` and archives left by a previous process, is safe.
 * - Optional time-based rotation ([Rotation.maxAgeMillis]) evaluated on the
 *   next write (an idle sink rotates lazily, not on a timer).
 * - Optional gzip archives (`<name>.<n>.gz`) and a total-disk cap
 *   ([Rotation.maxTotalBytes]) that prunes the oldest archives.
 * - Single-process writer; concurrent processes on one file are undefined.
 * - IOExceptions propagate to the dispatch engine's [ErrorMode][io.ilogger.core.ErrorMode].
 *
 * @param file target file path (created with parent directories).
 * @param codec text codec; use JSON codecs from `ilogger-serialization` for machine consumption.
 * @param append open in append mode (true) or truncate (false).
 * @param flushEveryWrite flush the OS buffer after each event (Durability.OS) or
 *   batch internally until [flush] (Durability.BUFFERED).
 * @param rotation optional size/time rotation and retention policy.
 * @param fsyncEveryWrite force each write to the storage device. Implies an
 *   OS flush and is significantly slower; use for audit data that must survive
 *   power loss.
 * @param ioDispatcher dispatcher for suspend [write]/[flush] calls. Defaults
 *   to a dedicated single-threaded view of [Dispatchers.IO] named
 *   `ilogger-file-io`, so blocking file IO never runs on
 *   [Dispatchers.Default] and concurrent writers to one sink are serialized.
 *   [writeDirect] always runs synchronously on the caller.
 * @param clock time source for [Rotation.maxAgeMillis].
 * @param cipher optional at-rest encryption (see
 *   [io.ilogger.core.crypto.AesGcmCipher]): when set, each encoded event is
 *   stored as one self-describing ciphertext token, so the file is unreadable
 *   and tamper-evident without the keys. Rotation, gzip and fsync compose
 *   unchanged; the key is fetched per record, so the provider can rotate
 *   mid-stream. Note the encrypted token is longer than the plaintext, which
 *   shifts effective `maxBytesPerFile`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class TextFileSink(
    file: Path,
    private val codec: EventCodec = DefaultTextEventCodec,
    append: Boolean = true,
    private val flushEveryWrite: Boolean = true,
    private val rotation: Rotation? = null,
    private val fsyncEveryWrite: Boolean = false,
    ioDispatcher: CoroutineDispatcher? = null,
    private val clock: LogClock = SystemClock,
    private val cipher: TextCipher? = null,
) : DirectLogSink {

    private val ioDispatcher: CoroutineDispatcher =
        ioDispatcher ?: Dispatchers.IO.limitedParallelism(1, name = "ilogger-file-io")

    /** Size-, time- and disk-based rotation policy. */
    public class Rotation(
        public val maxBytesPerFile: Long,
        public val maxFiles: Int,
        /** Maximum age of the active file in milliseconds, checked on write. */
        public val maxAgeMillis: Long? = null,
        /** Compress archived files as `<name>.<n>.gz`. */
        public val gzip: Boolean = false,
        /** Maximum total bytes of the active file plus archives; oldest archives are pruned first. */
        public val maxTotalBytes: Long? = null,
    ) {
        init {
            require(maxBytesPerFile > 0) { "maxBytesPerFile must be positive" }
            require(maxFiles >= 0) { "maxFiles must be >= 0" }
            require(maxAgeMillis == null || maxAgeMillis > 0) { "maxAgeMillis must be positive" }
            require(maxTotalBytes == null || maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        }
    }

    private val lock = Any()
    private val file: Path
    private var writer: BufferedWriter? = null
    private var channel: FileChannel? = null
    private var writtenBytes: Long = 0
    private var openedAtMillis: Long = 0

    init {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        this.file = file
        this.writer = open(file, append)
    }

    private fun open(file: Path, append: Boolean): BufferedWriter {
        val options = if (append) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        } else {
            arrayOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
        closeCurrent()
        val opened = FileChannel.open(file, *options)
        channel = opened
        writtenBytes = if (append) opened.size() else 0
        openedAtMillis = clock.nowMillis()
        return BufferedWriter(Channels.newWriter(opened, StandardCharsets.UTF_8), 16 * 1024)
    }

    override suspend fun write(event: LogEvent) {
        withContext(ioDispatcher) { writeDirect(event) }
    }

    override fun writeDirect(event: LogEvent) {
        val stored = cipher?.encrypt(codec.encode(event)) ?: codec.encode(event)
        val line = stored + "\n"
        val bytes = line.toByteArray(StandardCharsets.UTF_8).size.toLong()
        synchronized(lock) {
            val policy = rotation
            if (policy != null && writer != null && writtenBytes > 0 && shouldRoll(policy, bytes)) roll()
            receiver().write(line)
            writtenBytes += bytes
            if (flushEveryWrite) receiver().flush()
            if (fsyncEveryWrite) channel?.force(false)
            val cap = policy?.maxTotalBytes
            if (cap != null) pruneToTotalSize(policy, cap)
        }
    }

    override suspend fun flush() {
        withContext(ioDispatcher) { flushNow() }
    }

    private fun flushNow() {
        synchronized(lock) {
            writer?.flush()
            if (fsyncEveryWrite) channel?.force(false)
        }
    }

    private fun shouldRoll(policy: Rotation, incomingBytes: Long): Boolean {
        if (writtenBytes + incomingBytes > policy.maxBytesPerFile) {
            // writtenBytes > 0 in writeDirect guarantees an oversized event is
            // stored once instead of rolling an empty file forever.
            return true
        }
        val maxAge = policy.maxAgeMillis ?: return false
        return clock.nowMillis() - openedAtMillis >= maxAge
    }

    /** Rolls to the next file (callers hold [lock]). */
    private fun roll() {
        val policy = rotation!!
        val maxFiles = policy.maxFiles
        closeCurrent()
        try {
            if (maxFiles <= 0) {
                Files.deleteIfExists(file)
            } else if (policy.gzip) {
                shiftArchives(maxFiles, gzip = true)
                if (Files.exists(file)) gzipInto(file, archivePath(1, gzip = true))
                Files.deleteIfExists(file)
            } else {
                shiftArchives(maxFiles, gzip = false)
                if (Files.exists(file)) {
                    Files.move(file, archivePath(1, gzip = false), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } catch (error: IOException) {
            throw IOException(
                "Failed to rotate $file (maxFiles=$maxFiles, gzip=${policy.gzip}). " +
                    "Check write permissions and free disk space.",
                error,
            )
        }
        writer = open(file, append = false)
    }

    private fun shiftArchives(maxFiles: Int, gzip: Boolean) {
        Files.deleteIfExists(archivePath(maxFiles, gzip))
        for (index in maxFiles - 1 downTo 1) {
            val from = archivePath(index, gzip)
            if (Files.exists(from)) {
                Files.move(from, archivePath(index + 1, gzip), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun archivePath(index: Int, gzip: Boolean): Path =
        file.resolveSibling(if (gzip) "$file.$index.gz" else "$file.$index")

    private fun gzipInto(source: Path, target: Path) {
        Files.newOutputStream(
            target,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { output ->
            GZIPOutputStream(output).use { gzip -> Files.copy(source, gzip) }
        }
    }

    private fun pruneToTotalSize(policy: Rotation, cap: Long) {
        while (totalBytes(policy) > cap) {
            val oldest = (1..policy.maxFiles)
                .map { index -> archivePath(index, policy.gzip) }
                .lastOrNull { archive -> Files.exists(archive) }
                ?: return
            Files.deleteIfExists(oldest)
        }
    }

    private fun totalBytes(policy: Rotation): Long {
        var total = if (Files.exists(file)) Files.size(file) else 0
        for (index in 1..policy.maxFiles) {
            val archive = archivePath(index, policy.gzip)
            if (Files.exists(archive)) total += Files.size(archive)
        }
        return total
    }

    private fun closeCurrent() {
        val current = writer
        writer = null
        val openChannel = channel
        channel = null
        if (current != null) {
            try {
                current.flush()
            } catch (_: Throwable) {
            }
            try {
                if (fsyncEveryWrite) openChannel?.force(false)
            } catch (_: Throwable) {
            }
            try {
                current.close()
            } catch (_: Throwable) {
            }
        } else {
            try {
                openChannel?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun receiver(): BufferedWriter {
        val current = writer
        checkNotNull(current) { "TextFileSink is closed" }
        return current
    }

    override fun close() {
        synchronized(lock) {
            closeCurrent()
        }
    }
}
