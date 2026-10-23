package io.ilogger.room

import io.ilogger.core.LogEvent
import io.ilogger.core.sink.EventCodec
import io.ilogger.serialization.JsonEventCodec

/**
 * Projects an event into [RoomLogEntity]. Flat fields are copied for indexed
 * queries; [RoomLogEntity.payload] receives [codec]'s lossless encoding of the
 * whole event (JSON by default), optionally encrypted with [cipher].
 */
public fun LogEvent.toRoomLogEntity(
    codec: EventCodec = JsonEventCodec,
): RoomLogEntity = toRoomLogEntity(codec = codec, cipher = null)

/**
 * Variant with a payload cipher: [payload][RoomLogEntity.payload] is
 * `cipher.encrypt(codec.encode(event))`. Metadata columns stay plaintext so
 * queries and retention keep working.
 */
public fun LogEvent.toRoomLogEntity(
    codec: EventCodec = JsonEventCodec,
    cipher: RoomLogPayloadCipher?,
): RoomLogEntity {
    val encoded = codec.encode(this)
    return RoomLogEntity(
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
        payload = cipher?.encrypt(encoded) ?: encoded,
    )
}
