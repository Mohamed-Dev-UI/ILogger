package io.ilogger.remote

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Why a batch was dead-lettered.
 *
 * @property description human-readable cause, e.g. `HTTP 400` or `connection refused`
 * @property status HTTP status when the failure was a response
 */
public data class RemoteFailure(
    public val description: String,
    public val status: Int? = null,
)

/**
 * Final destination for batches that cannot be delivered.
 *
 * Called for permanent HTTP failures (4xx other than 408/429) and, when no
 * spool is configured, for exhausted retryable failures too. Implementations
 * must not throw — the sink contains and reports a throwing handler, but the
 * batch is already gone at that point.
 */
public fun interface RemoteDeadLetter {
    /** Persists or forwards one failed batch. */
    public suspend fun deliver(batch: RemoteBatch, failure: RemoteFailure)
}

/**
 * Appends dead-lettered events to a JSONL file, one encoded event per line.
 *
 * The file is never truncated or rotated by the library; sizing and cleanup
 * are the application's responsibility. Parent directories are created on
 * first write. Lines are importable with `JsonEventCodec.decode` or
 * `RoomLogDao.importJsonLines`.
 */
public class FileRemoteDeadLetter(
    public val file: Path,
) : RemoteDeadLetter {

    override suspend fun deliver(batch: RemoteBatch, failure: RemoteFailure): Unit = withContext(Dispatchers.IO) {
        file.parent?.let(Files::createDirectories)
        Files.writeString(
            file,
            batch.lines.joinToString("\n", postfix = "\n"),
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
