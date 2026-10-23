package io.ilogger.room

import java.util.concurrent.CopyOnWriteArraySet

/**
 * A legal hold: rows matched by an active hold are never purged or erased until
 * the hold is released.
 *
 * Holds are ordinary values, so an application can persist them in its own
 * preferences/table and [RoomLogLegalHoldRegistry.hold] them again on startup:
 *
 * ```kotlin
 * holds.hold(RoomLogLegalHold.Actor("user-42"))
 * holds.hold(RoomLogLegalHold.Entity("Order", "order-7"))
 * holds.hold(RoomLogLegalHold.Category("security"))
 * ```
 *
 * A hold is matched against the indexed columns of a row, so it works without
 * decoding the payload — and keeps working for encrypted payloads.
 */
public sealed interface RoomLogLegalHold {

    /** Whether [row] is protected by this hold. */
    public fun matches(row: RoomLogEntity): Boolean

    /** Protects exactly one event. */
    public data class Event(public val eventId: String) : RoomLogLegalHold {
        init {
            require(eventId.isNotBlank()) { "eventId must not be blank" }
        }

        override fun matches(row: RoomLogEntity): Boolean = row.eventId == eventId
    }

    /** Protects every event with this actor id. */
    public data class Actor(public val actorId: String) : RoomLogLegalHold {
        init {
            require(actorId.isNotBlank()) { "actorId must not be blank" }
        }

        override fun matches(row: RoomLogEntity): Boolean = row.actorId == actorId
    }

    /**
     * Protects every event of [entity], or exactly one instance when
     * [entityId] is provided.
     */
    public data class Entity(public val entity: String, public val entityId: String? = null) : RoomLogLegalHold {
        init {
            require(entity.isNotBlank()) { "entity must not be blank" }
            require(entityId == null || entityId.isNotBlank()) { "entityId must not be blank" }
        }

        override fun matches(row: RoomLogEntity): Boolean =
            row.entity == entity && (entityId == null || row.entityId == entityId)
    }

    /** Protects every event routed to this category. */
    public data class Category(public val category: String) : RoomLogLegalHold {
        init {
            require(category.isNotBlank()) { "category must not be blank" }
        }

        override fun matches(row: RoomLogEntity): Boolean = row.category == category
    }

    /** Protects every event with this action. */
    public data class Action(public val action: String) : RoomLogLegalHold {
        init {
            require(action.isNotBlank()) { "action must not be blank" }
        }

        override fun matches(row: RoomLogEntity): Boolean = row.action == action
    }
}

/**
 * Thread-safe set of active [RoomLogLegalHold]s consulted by
 * [RoomLogRetentionEnforcer] and [RoomLogErasure].
 *
 * The registry keeps holds in memory only. Persisting them — and re-applying
 * them at startup — is the application's responsibility; use [snapshot] to
 * persist and [hold] to restore.
 */
public class RoomLogLegalHoldRegistry {

    private val holds = CopyOnWriteArraySet<RoomLogLegalHold>()

    /** Activates [hold]; re-holding an equal hold is a no-op. */
    public fun hold(hold: RoomLogLegalHold) {
        holds.add(hold)
    }

    /** Activates every hold in [holds]. */
    public fun hold(vararg holds: RoomLogLegalHold) {
        this.holds.addAll(holds)
    }

    /** Deactivates [hold]; returns whether it was active. */
    public fun release(hold: RoomLogLegalHold): Boolean = holds.remove(hold)

    /** Removes every hold. */
    public fun clear() {
        holds.clear()
    }

    /** Whether any active hold matches [row]. */
    public fun isHeld(row: RoomLogEntity): Boolean = holds.any { it.matches(row) }

    /** Immutable snapshot of the active holds, suitable for persistence. */
    public fun snapshot(): Set<RoomLogLegalHold> = holds.toSet()

    /** Number of active holds. */
    public val size: Int
        get() = holds.size
}
