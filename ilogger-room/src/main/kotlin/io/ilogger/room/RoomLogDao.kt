package io.ilogger.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for [RoomLogEntity], implemented by Room in the consuming module.
 *
 * Do **not** annotate this DAO (or a wrapper around it) with `@Log`: the log
 * pipeline must not feed itself. `insertAll` uses
 * [OnConflictStrategy.REPLACE] so redelivery of the same event id is
 * idempotent.
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
}
