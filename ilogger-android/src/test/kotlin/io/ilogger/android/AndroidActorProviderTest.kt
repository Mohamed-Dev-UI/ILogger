package io.ilogger.android

import io.ilogger.core.ActorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidActorProviderTest {

    private class FakeStore(var stored: String? = null) : SessionStore {
        var saves: Int = 0

        override fun sessionId(): String? = stored

        override fun saveSessionId(id: String) {
            stored = id
            saves++
        }
    }

    @Test
    fun `generates and persists a session id on first use`() {
        val store = FakeStore()
        val provider = AndroidActorProvider(store) { "generated-session" }

        assertEquals("generated-session", provider.sessionId)
        assertEquals("generated-session", store.stored)
        assertEquals(1, store.saves)
    }

    @Test
    fun `reuses the stored session without generating another`() {
        val store = FakeStore(stored = "persisted-session")
        var generated = false
        val provider = AndroidActorProvider(store) {
            generated = true
            "new-session"
        }

        assertEquals("persisted-session", provider.sessionId)
        assertEquals(0, store.saves)
        assertTrue(!generated)
    }

    @Test
    fun `anonymous sessions are automation actors keyed by the session`() {
        val provider = AndroidActorProvider(FakeStore(stored = "s-1")) { "unused" }

        val actor = provider.currentActor()

        assertEquals(ActorType.AUTOMATION, actor.type)
        assertEquals("s-1", actor.id)
    }

    @Test
    fun `setUser supplies a user actor augmented with the session`() {
        val provider = AndroidActorProvider(FakeStore(stored = "s-2")) { "unused" }

        provider.setUser("u-1", name = "Grace", attributes = mapOf("team" to "audit"))
        val actor = provider.currentActor()

        assertEquals(ActorType.USER, actor.type)
        assertEquals("u-1", actor.id)
        assertEquals("Grace", actor.name)
        assertEquals("audit", actor.attributes["team"])
        assertEquals("s-2", actor.attributes[AndroidActorProvider.SESSION_ATTRIBUTE])
    }

    @Test
    fun `clearUser falls back to the session actor`() {
        val provider = AndroidActorProvider(FakeStore(stored = "s-3")) { "unused" }
        provider.setUser("u-1")

        provider.clearUser()

        assertEquals(ActorType.AUTOMATION, provider.currentActor().type)
    }

    @Test
    fun `users are not persisted across provider instances`() {
        val store = FakeStore(stored = "s-4")
        AndroidActorProvider(store) { "unused" }.setUser("u-1")

        val restarted = AndroidActorProvider(store) { "unused" }

        assertEquals(ActorType.AUTOMATION, restarted.currentActor().type)
        assertNotEquals("u-1", restarted.currentActor().id)
    }
}
