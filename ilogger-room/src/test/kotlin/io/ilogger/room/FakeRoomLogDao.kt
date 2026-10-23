package io.ilogger.room

import androidx.room.RoomRawQuery
import java.util.Collections
import kotlinx.coroutines.flow.Flow

/** Recording [RoomLogDao] for unit tests; no Room runtime involved. */
class FakeRoomLogDao : RoomLogDao {

    val inserted: MutableList<RoomLogEntity> = Collections.synchronizedList(ArrayList())
    val purged: MutableList<Long> = Collections.synchronizedList(ArrayList())

    /** When set, [purgeBefore] throws it instead of recording a purge. */
    var purgeError: Throwable? = null

    override suspend fun insertAll(rows: List<RoomLogEntity>) {
        synchronized(inserted) { inserted.addAll(rows) }
    }

    override suspend fun recent(limit: Int): List<RoomLogEntity> =
        synchronized(inserted) { inserted.take(limit) }

    override suspend fun purgeBefore(cutoff: Long): Int {
        purgeError?.let { throw it }
        purged.add(cutoff)
        return 0
    }

    override suspend fun deleteByIds(eventIds: List<String>): Int =
        synchronized(inserted) {
            var removed = 0
            eventIds.forEach { id ->
                if (inserted.removeAll { it.eventId == id }) removed++
            }
            removed
        }

    override suspend fun expiredPage(
        cutoff: Long,
        afterTs: Long,
        afterId: String,
        limit: Int,
    ): List<RoomLogEntity> = synchronized(inserted) {
        inserted.asSequence()
            .filter { it.ts < cutoff }
            .filter { it.ts > afterTs || (it.ts == afterTs && it.eventId > afterId) }
            .sortedWith(compareBy<RoomLogEntity> { it.ts }.thenBy { it.eventId })
            .take(limit)
            .toList()
    }

    override suspend fun queryRaw(query: RoomRawQuery): List<RoomLogEntity> =
        unsupported("queryRaw")

    override fun observeRaw(query: RoomRawQuery): Flow<List<RoomLogEntity>> =
        unsupported("observeRaw")

    override suspend fun countRaw(query: RoomRawQuery): List<RoomLogCount> =
        unsupported("countRaw")

    override fun observeCountRaw(query: RoomRawQuery): Flow<List<RoomLogCount>> =
        unsupported("observeCountRaw")

    override suspend fun distinctRaw(query: RoomRawQuery): List<RoomLogValue> =
        unsupported("distinctRaw")

    private fun unsupported(method: String): Nothing =
        throw UnsupportedOperationException("$method executes SQL; use a real Room database in this test")
}
