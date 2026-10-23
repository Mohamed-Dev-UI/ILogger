package io.ilogger.room

/**
 * Thrown when a [RoomLogTarget] must persist events but its DAO was never
 * supplied.
 *
 * Produced by write attempts after [RoomLogTarget]'s configured bind timeout
 * and by [RoomLogTarget.close] when the target is still unbound, so a missing
 * `bind(dao)` call fails loudly instead of silently losing events.
 */
public class RoomLogTargetUnboundException(message: String) : IllegalStateException(message)
