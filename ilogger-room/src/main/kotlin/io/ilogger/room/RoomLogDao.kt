package io.ilogger.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [RoomLogEntity], implemented by Room in the consuming module.
 *
 * Do **not** annotate this DAO (or a wrapper around it) with `@Log`: the log
 * pipeline must not feed itself. `insertAll` uses
 * [OnConflictStrategy.REPLACE] so redelivery of the same event id is
 * idempotent.
 *
 * The `*Raw` methods are the low-level escape hatch used by the typed
 * [RoomLogFilter] helpers in `RoomLogQueries.kt`. Application code should
 * prefer [query], [observe], [count], [observeCount] and [distinctValues].
 */
@Dao
public interface RoomLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(rows: List<RoomLogEntity>)

    /** Most recent rows first; stable order for equal timestamps. */
    @Query("SELECT * FROM ilogger_events ORDER BY ts DESC, eventId DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<RoomLogEntity>

    /** Deletes rows older than [cutoff] epoch millis; returns the row count. */
    @Query("DELETE FROM ilogger_events WHERE ts < :cutoff")
    public suspend fun purgeBefore(cutoff: Long): Int

    /** Deletes the given event ids; returns the number of rows removed. */
    @Query("DELETE FROM ilogger_events WHERE eventId IN (:eventIds)")
    public suspend fun deleteByIds(eventIds: List<String>): Int

    /**
     * One keyset page of rows older than [cutoff], ordered by `(ts, eventId)`
     * after `(afterTs, afterId)`. Used by compliance sweeps so held rows never
     * stall a scan.
     */
    @Query(
        """
        SELECT * FROM ilogger_events
        WHERE ts < :cutoff
          AND (ts > :afterTs OR (ts = :afterTs AND eventId > :afterId))
        ORDER BY ts ASC, eventId ASC
        LIMIT :limit
        """,
    )
    public suspend fun expiredPage(
        cutoff: Long,
        afterTs: Long,
        afterId: String,
        limit: Int,
    ): List<RoomLogEntity>

    // ------------------------------------------------------------------
    // Raw query surface (see RoomLogQueries.kt for the typed helpers)
    // ------------------------------------------------------------------

    @RawQuery
    public suspend fun queryRaw(query: RoomRawQuery): List<RoomLogEntity>

    @RawQuery(observedEntities = [RoomLogEntity::class])
    public fun observeRaw(query: RoomRawQuery): Flow<List<RoomLogEntity>>

    @RawQuery
    public suspend fun countRaw(query: RoomRawQuery): List<RoomLogCount>

    @RawQuery(observedEntities = [RoomLogEntity::class])
    public fun observeCountRaw(query: RoomRawQuery): Flow<List<RoomLogCount>>

    @RawQuery
    public suspend fun distinctRaw(query: RoomRawQuery): List<RoomLogValue>
}
