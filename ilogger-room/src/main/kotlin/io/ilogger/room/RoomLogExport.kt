package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Outcome
import java.io.BufferedReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private const val DEFAULT_PAGE_SIZE: Int = 500

/**
 * Writes matching rows to [writer] as JSON Lines (one compact JSON object per
 * line, oldest first) and returns the number of lines written.
 *
 * The `payload` column is already the encoded event (JSON by default), so
 * export is lossless and does not re-encode. Pass [cipher] when rows were
 * written with [RoomLogTarget]'s `payloadCipher`; otherwise the ciphertext
 * would be exported as-is and [importJsonLines] could not read it back.
 *
 * ```kotlin
 * file.bufferedWriter().use { out ->
 *     val count = logDao.exportJsonLines(out, RoomLogFilter(levels = setOf(LogLevel.AUDIT)))
 * }
 * ```
 *
 * Export walks the table with offset pages. Flush the logger first
 * (`logger.flush()`) and avoid concurrent writers if you need a stable
 * snapshot: inserts during export can shift offsets and duplicate or skip a
 * row. Re-importing is idempotent regardless, because the event id is the
 * primary key.
 *
 * @throws IllegalStateException when a payload is not a JSON object (usually a
 *   non-JSON codec or a missing [cipher]).
 */
public suspend fun RoomLogDao.exportJsonLines(
    writer: Appendable,
    filter: RoomLogFilter = RoomLogFilter(),
    pageSize: Int = DEFAULT_PAGE_SIZE,
    cipher: RoomLogPayloadCipher? = null,
): Int {
    require(pageSize > 0) { "pageSize must be > 0, got $pageSize" }

    var offset = 0
    var written = 0
    while (true) {
        val rows = query(
            filter = filter,
            page = RoomLogPage(limit = pageSize, offset = offset, order = RoomLogOrder.OLDEST_FIRST),
        )
        if (rows.isEmpty()) break

        rows.forEach { row ->
            val payload = cipher?.decrypt(row.payload) ?: row.payload
            check(payload.trimStart().startsWith("{")) {
                "Event ${row.eventId} has a payload that is not a JSON object. " +
                    "Export requires JSON payloads (JsonEventCodec) and the same " +
                    "RoomLogPayloadCipher used at write time."
            }
            writer.append(payload).append('\n')
            written++
        }

        if (rows.size < pageSize) break
        offset += rows.size
    }
    return written
}

/**
 * Reads JSON Lines produced by [exportJsonLines] (or by any JSONL sink using
 * `JsonEventCodec`), inserts them through [RoomLogDao.insertAll] in batches,
 * and returns the number of rows imported.
 *
 * Each line must contain at least `id`, `timestampMillis`, `action` and
 * `outcome`; `level` defaults to `AUDIT` and `actionVersion` to `1`. The flat
 * columns are projected from the JSON, while `payload` keeps the original line
 * verbatim so unknown fields survive a round trip. Rows are inserted with
 * `REPLACE`, so importing the same file twice is safe.
 *
 * The reader is **not** closed. Blank lines are skipped. A malformed line
 * fails the import with its line number; batches inserted before the failure
 * stay in the table (re-run after fixing the file).
 *
 * Pass [cipher] to encrypt the payload for a table written with
 * [RoomLogTarget]'s `payloadCipher`.
 */
public suspend fun RoomLogDao.importJsonLines(
    reader: BufferedReader,
    cipher: RoomLogPayloadCipher? = null,
    batchSize: Int = DEFAULT_PAGE_SIZE,
): Int {
    require(batchSize > 0) { "batchSize must be > 0, got $batchSize" }

    val batch = ArrayList<RoomLogEntity>(batchSize)
    var imported = 0
    var lineNumber = 0

    while (true) {
        val line = reader.readLine() ?: break
        lineNumber++
        if (line.isBlank()) continue

        batch += parseImportedRow(line, lineNumber, cipher)
        if (batch.size >= batchSize) {
            insertAll(batch.toList())
            imported += batch.size
            batch.clear()
        }
    }
    if (batch.isNotEmpty()) {
        insertAll(batch)
        imported += batch.size
    }
    return imported
}

private fun parseImportedRow(
    line: String,
    lineNumber: Int,
    cipher: RoomLogPayloadCipher?,
): RoomLogEntity {
    val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrElse { error ->
        throw IllegalArgumentException("Line $lineNumber is not a JSON object: ${error.message}", error)
    }

    fun required(key: String): String = obj.text(key)
        ?: throw IllegalArgumentException("Line $lineNumber is missing '$key'")

    val eventId = required("id")
    val timestamp = obj["timestampMillis"]?.let { (it as? JsonPrimitive)?.longOrNull }
        ?: throw IllegalArgumentException("Line $lineNumber is missing numeric 'timestampMillis'")
    val action = required("action")
    val levelText = obj.text("level") ?: LogLevel.AUDIT.name
    val level = LogLevel.entries.firstOrNull { it.name.equals(levelText, ignoreCase = true) }
        ?: throw IllegalArgumentException("Line $lineNumber has unknown level '$levelText'")
    val outcomeText = required("outcome")
    val outcome = Outcome.entries.firstOrNull { it.name.equals(outcomeText, ignoreCase = true) }
        ?: throw IllegalArgumentException("Line $lineNumber has unknown outcome '$outcomeText'")

    return RoomLogEntity(
        eventId = eventId,
        ts = timestamp,
        level = level.name,
        action = action,
        actionVersion = obj["actionVersion"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 1,
        entity = obj.text("entity"),
        entityId = obj.text("entityId"),
        actorId = (obj["actor"] as? JsonObject)?.text("id"),
        outcome = outcome.name,
        source = obj.text("source"),
        category = obj.text("category"),
        correlationId = obj.text("correlationId"),
        causationId = obj.text("causationId"),
        payload = cipher?.encrypt(line) ?: line,
    )
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
