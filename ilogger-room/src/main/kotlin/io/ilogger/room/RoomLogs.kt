package io.ilogger.room

import io.ilogger.core.ILoggerBuilder
import io.ilogger.core.sink.EventCodec
import io.ilogger.serialization.JsonEventCodec

/**
 * Persists every event into [RoomLogEntity] through [dao].
 *
 * This is the whole wiring for the common case:
 *
 * ```kotlin
 * val logger = ILogger.create {
 *     sink(ConsoleLogSink())
 *     roomLogs(db.logDao(), retention = RoomLogRetention.keepDays(30))
 * }
 * ```
 *
 * The engine's buffered dispatch (the default) already hands the sink real
 * batches, so no dispatch configuration is required. For lifecycle events
 * emitted while the database is being built, create a [RoomLogTarget] before
 * the logger, pass it with `sink(target)`, and call
 * [RoomLogTarget.bind] after `build()`.
 */
public fun ILoggerBuilder.roomLogs(
    dao: RoomLogDao,
    codec: EventCodec = JsonEventCodec,
    retention: RoomLogRetention? = null,
): Unit = sink(
    RoomLogTarget(codec = codec, retention = retention).also { target -> target.bind(dao) },
)
