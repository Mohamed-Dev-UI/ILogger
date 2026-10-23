package io.ilogger.remote

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Durable buffer for events that could not be delivered yet.
 *
 * [RemoteLogSink] appends encoded lines here before sending and removes them
 * only after the server accepted them, so a crash or an offline device loses
 * nothing that fit in the spool. Implement this interface to back the buffer
 * with SQLite or another store; [FileRemoteSpool] is the shipped JSONL
 * implementation.
 *
 * Implementations may assume a single writer (the sink serializes calls) but
 * should still be safe across process restarts. [peek] must return events in
 * append order.
 */
public interface RemoteSpool {

    /**
     * Appends [lines], one event per line. Returns `false` when the spool is
     * full and the lines were not stored.
     */
    public suspend fun append(lines: List<String>): Boolean

    /** Up to [limit] oldest lines, in append order. */
    public suspend fun peek(limit: Int): List<String>

    /** Removes the [count] oldest lines. */
    public suspend fun removeFirst(count: Int)

    /** Current size in bytes (approximate for non-file stores). */
    public suspend fun sizeBytes(): Long
}

/**
 * JSONL file-backed [RemoteSpool], one encoded event per line.
 *
 * The file is append-only while events arrive and atomically rewritten when
 * delivered lines are removed (temporary file + `ATOMIC_MOVE`), so a crash
 * leaves either the old or the new queue, never a half-truncated one. Parent
 * directories are created on first append.
 *
 * A single process may write the spool; running two loggers over one file is
 * not supported. Pick a path in app-private storage — the file contains
 * plaintext events, so encrypt or restrict it when payloads are sensitive.
 *
 * @property file spool path; the file is created on first append
 * @property maxBytes hard limit checked before every append
 */
public class FileRemoteSpool(
    public val file: Path,
    public val maxBytes: Long = DEFAULT_MAX_BYTES,
) : RemoteSpool {

    init {
        require(maxBytes > 0) { "maxBytes must be > 0, got $maxBytes" }
    }

    private val mutex = Mutex()

    override suspend fun append(lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        require(lines.none { '\n' in it || '\r' in it }) { "spool lines must be single-line strings" }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                file.parent?.let(Files::createDirectories)
                val bytes = lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
                val current = if (Files.exists(file)) Files.size(file) else 0L
                if (current + bytes.size > maxBytes) return@withContext false
                Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                true
            }
        }
    }

    override suspend fun peek(limit: Int): List<String> {
        require(limit > 0) { "limit must be > 0, got $limit" }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                if (!Files.exists(file)) return@withContext emptyList()
                Files.newBufferedReader(file, Charsets.UTF_8).use { reader ->
                    val lines = ArrayList<String>(minOf(limit, 256))
                    while (lines.size < limit) {
                        val line = reader.readLine() ?: break
                        if (line.isNotEmpty()) lines += line
                    }
                    lines
                }
            }
        }
    }

    override suspend fun removeFirst(count: Int) {
        if (count <= 0) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (!Files.exists(file)) return@withContext
                val remaining = Files.readAllLines(file, Charsets.UTF_8).drop(count)
                if (remaining.isEmpty()) {
                    Files.deleteIfExists(file)
                    return@withContext
                }
                val temp = file.resolveSibling("${file.fileName}.tmp")
                Files.writeString(
                    temp,
                    remaining.joinToString("\n", postfix = "\n"),
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    override suspend fun sizeBytes(): Long = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (Files.exists(file)) Files.size(file) else 0L
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024
    }
}
