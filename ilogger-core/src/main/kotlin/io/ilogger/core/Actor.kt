package io.ilogger.core

/**
 * Who performed an action.
 *
 * ILogger does not assume any application's authentication model: an [Actor]
 * is a small, serializable description. Applications (or framework plugins)
 * decide where actors come from — a thread-local session, an HTTP header, a
 * DI-scoped user, or nothing at all.
 *
 * Instances are immutable. Attribute maps are deliberately small and flat.
 */
public class Actor private constructor(
    public val id: String?,
    public val type: ActorType,
    public val name: String?,
    public val attributes: Map<String, String>,
) {
    public companion object {
        public fun user(id: String, name: String? = null, attributes: Map<String, String> = emptyMap()): Actor =
            Actor(checkId(id), ActorType.USER, name, attributes)

        public fun system(attributes: Map<String, String> = emptyMap()): Actor =
            Actor(null, ActorType.SYSTEM, null, attributes)

        public fun worker(id: String? = null, name: String? = null, attributes: Map<String, String> = emptyMap()): Actor =
            Actor(id?.let(::checkId), ActorType.WORKER, name, attributes)

        public fun automation(id: String? = null, name: String? = null, attributes: Map<String, String> = emptyMap()): Actor =
            Actor(id?.let(::checkId), ActorType.AUTOMATION, name, attributes)

        public fun plugin(id: String? = null, name: String? = null, attributes: Map<String, String> = emptyMap()): Actor =
            Actor(id?.let(::checkId), ActorType.PLUGIN, name, attributes)

        public fun service(id: String? = null, name: String? = null, attributes: Map<String, String> = emptyMap()): Actor =
            Actor(id?.let(::checkId), ActorType.SERVICE, name, attributes)

        /** Anonymous/unauthenticated actor. Events still record that *someone* acted. */
        public fun anonymous(): Actor = Actor(null, ActorType.ANONYMOUS, null, emptyMap())

        public fun custom(id: String?, name: String? = null, attributes: Map<String, String> = emptyMap()): Actor =
            Actor(id?.let(::checkId), ActorType.CUSTOM, name, attributes)

        private fun checkId(id: String): String {
            require(id.isNotBlank()) { "Actor id must not be blank" }
            return id
        }
    }

    /** Internal projection used by the resource-limit sanitizer; preserves the type. */
    internal fun copyWith(
        id: String? = this.id,
        name: String? = this.name,
        attributes: Map<String, String> = this.attributes,
    ): Actor = Actor(id, type, name, attributes)

    override fun equals(other: Any?): Boolean =
        other is Actor && other.id == id && other.type == type && other.name == name && other.attributes == attributes
    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + type.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + attributes.hashCode()
        return result
    }

    override fun toString(): String {
        val who = id ?: type.name.lowercase()
        return name?.let { "$who($it)" } ?: who
    }
}

/** Coarse actor categories. Custom identity systems map onto these or use [ActorType.CUSTOM]. */
public enum class ActorType {
    USER, SYSTEM, WORKER, AUTOMATION, PLUGIN, SERVICE, ANONYMOUS, CUSTOM
}

/**
 * Supplies the actor for the *current* operation when no more specific actor
 * is available (see the precedence documentation in [ILogger.record]).
 *
 * Implementations must be thread-safe and cheap: they run on the audit-event
 * path. Typical implementations read from `LogContext`, an application-owned
 * session, or a per-thread holder.
 */
public fun interface ActorProvider {
    public fun currentActor(): Actor?
}
