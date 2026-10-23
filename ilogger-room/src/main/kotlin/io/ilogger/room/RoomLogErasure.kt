package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogClock
import io.ilogger.core.LogEvent
import io.ilogger.core.SystemClock
import java.security.MessageDigest
import java.util.UUID

/** Action of the audit event written by [RoomLogErasure] for every request. */
public const val ROOM_LOG_ERASURE_ACTION: String = "compliance.erasure"

/** Category of the erasure audit event, so category-based retention can cover it. */
public const val ROOM_LOG_ERASURE_CATEGORY: String = "compliance"

/**
 * Outcome of one erasure request.
 *
 * @property targetKind `"actor"` or `"entity"`
 * @property targetHash first 16 hex characters of SHA-256 over the erased
 *   identifier — pseudonymous linkage to the request, never the raw value
 * @property removed rows deleted
 * @property withheld rows matched by a [RoomLogLegalHold] and therefore kept
 * @property auditEventId id of the deletion-audit event written to the store
 */
public data class RoomLogErasureReport(
    public val targetKind: String,
    public val targetHash: String,
    public val removed: Int,
    public val withheld: Int,
    public val auditEventId: String,
)

/**
 * GDPR-style subject erasure over [RoomLogDao] with a deletion audit.
 *
 * [eraseActor] and [eraseEntity] collect every matching row, keep rows matched
 * by [holds], delete the rest in chunks, and then insert one audit event
 * ([ROOM_LOG_ERASURE_ACTION]) recording the pseudonymous target, the counts,
 * the reason and an optional caller request id. The audit event is the only
 * trace left: no raw actor/entity id and no payload of the erased rows is
 * retained.
 *
 * ```kotlin
 * val report = RoomLogErasure(db.logDao(), holds = holds)
 *     .eraseActor(actorId = "user-42", reason = "GDPR Art. 17 request", requestId = "DSAR-2026-118")
 * ```
 *
 * Erasure only covers the shipped Room table. File sinks are append-only; age
 * their contents out with rotation ([io.ilogger.core.sink.TextFileSink]) or
 * rewrite an encrypted archive before deleting it. Erasure does not touch
 * event ids already shipped elsewhere — callers own downstream propagation.
 *
 * @property payloadCipher must match the one used by the target, so the audit
 *   event is stored in the same format as the rest of the table.
 */
public class RoomLogErasure(
    private val dao: RoomLogDao,
    private val holds: RoomLogLegalHoldRegistry = RoomLogLegalHoldRegistry(),
    private val clock: LogClock = SystemClock,
    private val payloadCipher: RoomLogPayloadCipher? = null,
) {

    /** Erases every event attributed to [actorId], unless a hold protects it. */
    public suspend fun eraseActor(
        actorId: String,
        reason: String = "subject erasure request",
        requestId: String? = null,
    ): RoomLogErasureReport {
        require(actorId.isNotBlank()) { "actorId must not be blank" }
        return erase(
            targetKind = "actor",
            targetValue = actorId,
            filter = RoomLogFilter(actorIds = setOf(actorId)),
            reason = reason,
            requestId = requestId,
        )
    }

    /** Erases every event of one [entity] instance, unless a hold protects it. */
    public suspend fun eraseEntity(
        entity: String,
        entityId: String,
        reason: String = "subject erasure request",
        requestId: String? = null,
    ): RoomLogErasureReport {
        require(entity.isNotBlank()) { "entity must not be blank" }
        require(entityId.isNotBlank()) { "entityId must not be blank" }
        return erase(
            targetKind = "entity",
            targetValue = "$entity:$entityId",
            filter = RoomLogFilter(entities = setOf(entity), entityIds = setOf(entityId)),
            reason = reason,
            requestId = requestId,
        )
    }

    private suspend fun erase(
        targetKind: String,
        targetValue: String,
        filter: RoomLogFilter,
        reason: String,
        requestId: String?,
    ): RoomLogErasureReport {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(requestId == null || requestId.isNotBlank()) { "requestId must not be blank" }

        val rows = collect(filter)
        val deletable = rows.filterNot { holds.isHeld(it) }
        val removed = deletable.chunked(DELETE_CHUNK_SIZE).sumOf { chunk ->
            dao.deleteByIds(chunk.map { it.eventId })
        }
        val withheld = rows.size - deletable.size
        val targetHash = shortHash(targetValue)

        val audit = LogEvent(
            id = UUID.randomUUID().toString(),
            timestampMillis = clock.nowMillis(),
            level = LogLevel.AUDIT,
            action = ROOM_LOG_ERASURE_ACTION,
            category = ROOM_LOG_ERASURE_CATEGORY,
            source = "RoomLogErasure",
            metadata = buildMap {
                put("targetKind", targetKind)
                put("targetHash", targetHash)
                put("removed", removed)
                put("withheld", withheld)
                put("reason", reason)
                requestId?.let { put("requestId", it) }
            },
        )
        dao.insertAll(listOf(audit.toRoomLogEntity(cipher = payloadCipher)))

        return RoomLogErasureReport(
            targetKind = targetKind,
            targetHash = targetHash,
            removed = removed,
            withheld = withheld,
            auditEventId = audit.id,
        )
    }

    private suspend fun collect(filter: RoomLogFilter): List<RoomLogEntity> {
        val rows = ArrayList<RoomLogEntity>()
        var offset = 0
        while (true) {
            val page = dao.query(
                filter = filter,
                page = RoomLogPage(limit = QUERY_PAGE_SIZE, offset = offset, order = RoomLogOrder.OLDEST_FIRST),
            )
            rows += page
            if (page.size < QUERY_PAGE_SIZE) break
            offset += page.size
        }
        return rows
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
            .take(HASH_PREFIX_LENGTH)
    }

    private companion object {
        const val QUERY_PAGE_SIZE: Int = 500
        const val DELETE_CHUNK_SIZE: Int = 500
        const val HASH_PREFIX_LENGTH: Int = 16
    }
}
