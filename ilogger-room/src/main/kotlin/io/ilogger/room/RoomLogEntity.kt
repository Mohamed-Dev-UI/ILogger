package io.ilogger.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Library-owned Room table for audit events.
 *
 * Add it to the application's `@Database(entities = [...])`; the primary key is
 * [io.ilogger.core.LogEvent.id], so `OnConflictStrategy.REPLACE` inserts make
 * retries and redelivery idempotent. The flat columns are for indexed queries,
 * while [payload] keeps the full lossless event (JSON by default).
 *
 * The schema is append-only within a major version. Applications adding the
 * table to an existing database use [RoomLogSchema.migration] so Room's
 * migration validation accepts it.
 */
@Entity(
    tableName = RoomLogSchema.TABLE,
    indices = [Index("ts"), Index("level"), Index("action")],
)
public data class RoomLogEntity(
    @PrimaryKey public val eventId: String,
    public val ts: Long,
    public val level: String,
    public val action: String,
    public val actionVersion: Int,
    public val entity: String?,
    public val entityId: String?,
    public val actorId: String?,
    public val outcome: String,
    public val source: String?,
    public val category: String?,
    public val correlationId: String?,
    public val causationId: String?,
    public val payload: String,
)
