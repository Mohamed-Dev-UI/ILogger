package io.ilogger.room

import io.ilogger.core.LogEvent
import io.ilogger.core.sink.EventCodec
import io.ilogger.serialization.JsonEventCodec

/**
 * Projects an event into [RoomLogEntity]. Flat fields are copied for indexed
 * queries; [RoomLogEntity.payload] receives [codec]'s lossless encoding of the
 * whole event (JSON by default).
 */
public fun LogEvent.toRoomLogEntity(
    codec: EventCodec = JsonEventCodec,
): RoomLogEntity = RoomLogEntity(
    eventId = id,
    ts = timestampMillis,
    level = level.name,
    action = action,
    actionVersion = actionVersion,
    entity = entity,
    entityId = entityId,
    actorId = actor?.id,
    outcome = outcome.name,
    source = source,
    category = category,
    correlationId = correlationId,
    causationId = causationId,
    payload = codec.encode(this),
)
