package io.ilogger.android

import android.content.Context
import android.content.SharedPreferences
import io.ilogger.core.Actor
import io.ilogger.core.ActorProvider
import java.util.UUID

/**
 * Supplies an actor for Android processes that do not have a user session yet.
 *
 * Two layers:
 * - a **session id** generated once per installation and persisted in
 *   [SharedPreferences], so events from one install share a stable automation
 *   actor across restarts;
 * - an optional **user** set by the application after sign-in. It is kept in
 *   memory only — process death ends the user session, which is the safe
 *   default for audit attribution.
 *
 * Actor shape:
 * - user set: [Actor.user] with the caller's attributes plus `session`;
 * - otherwise: [Actor.automation] whose id is the session id.
 *
 * [currentActor] is lock-free (one volatile read), so it is safe on the audit
 * path.
 */
public class AndroidActorProvider internal constructor(
    private val store: SessionStore,
    generateId: () -> String,
) : ActorProvider {

    public constructor(
        context: Context,
        preferencesName: String = PREFERENCES_NAME,
    ) : this(
        SharedPreferencesSessionStore(
            context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
        ),
        { UUID.randomUUID().toString() },
    )

    /** Stable id for this installation. */
    public val sessionId: String =
        store.sessionId() ?: generateId().also(store::saveSessionId)

    @Volatile
    private var user: Actor? = null

    /** Marks the current user; attributes are augmented with the session id. */
    public fun setUser(id: String, name: String? = null, attributes: Map<String, String> = emptyMap()) {
        user = Actor.user(id, name, attributes + (SESSION_ATTRIBUTE to sessionId))
    }

    /** Clears the current user (sign-out); the session actor remains. */
    public fun clearUser() {
        user = null
    }

    override fun currentActor(): Actor = user ?: Actor.automation(id = sessionId)

    public companion object {
        public const val PREFERENCES_NAME: String = "io.ilogger.android.session"
        public const val SESSION_ATTRIBUTE: String = "session"
    }
}

internal interface SessionStore {
    fun sessionId(): String?
    fun saveSessionId(id: String)
}

internal class SharedPreferencesSessionStore(
    private val preferences: SharedPreferences,
) : SessionStore {

    override fun sessionId(): String? =
        preferences.getString(KEY_SESSION_ID, null)?.takeIf { it.isNotBlank() }

    override fun saveSessionId(id: String) {
        preferences.edit().putString(KEY_SESSION_ID, id).apply()
    }

    private companion object {
        const val KEY_SESSION_ID = "session-id"
    }
}
