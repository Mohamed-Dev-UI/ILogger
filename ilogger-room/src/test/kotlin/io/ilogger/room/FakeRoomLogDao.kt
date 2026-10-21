package io.ilogger.room

/** Recording [RoomLogDao] for unit tests; no Room runtime involved. */
class FakeRoomLogDao : RoomLogDao {

    val inserted: MutableList<RoomLogEntity> = mutableListOf()
    val purged: MutableList<Long> = mutableListOf()

    /** When set, [purgeBefore] throws it instead of recording a purge. */
    var purgeError: Throwable? = null

    override suspend fun insertAll(rows: List<RoomLogEntity>) {
        inserted.addAll(rows)
    }

    override suspend fun recent(limit: Int): List<RoomLogEntity> = inserted.take(limit)

    override suspend fun purgeBefore(cutoff: Long): Int {
        purgeError?.let { throw it }
        purged.add(cutoff)
        return 0
    }
}
