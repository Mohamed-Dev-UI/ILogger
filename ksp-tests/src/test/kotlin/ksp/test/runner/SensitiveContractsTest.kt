package ksp.test.runner

import io.ilogger.core.LogMasking
import io.ilogger.testing.TestKit
import kotlinx.coroutines.test.runTest
import ksp.test.library.MaskedActorRepo
import ksp.test.library.OmitConfigRepo
import ksp.test.library.OmittedActorRepo
import ksp.test.library.PrivateTask
import ksp.test.library.ReturnSensitiveRepo
import ksp.test.library.SensitiveParamsRepo
import ksp.test.library.TaskActions
import ksp.test.library.logged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * G-23: parameter/type-level sensitivity marks, entry-level and container-wide
 * OMIT, and policy-driven actor sanitization.
 */
class SensitiveContractsTest {

    @Test
    fun `sensitive parameter is masked under an explicit policy`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            object : SensitiveParamsRepo {
                override suspend fun record(token: String, title: String): Long = 1L
                override suspend fun touch(task: PrivateTask): Long = task.id
            }.logged(logger).record("s3cr3t-token", "visible")

            val event = kit.assertLogged(action = TaskActions.PARAM_MASKED).single()
            assertEquals(LogMasking.MASK, event.metadata["token"])
            assertEquals("visible", event.metadata["title"])
        }
    }

    @Test
    fun `explicit OMIT metadata emits no key`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            object : SensitiveParamsRepo {
                override suspend fun record(token: String, title: String): Long = 1L
                override suspend fun touch(task: PrivateTask): Long = task.id
            }.logged(logger).touch(PrivateTask(7L, "kept"))

            val event = kit.assertLogged(action = TaskActions.OMIT_META).single()
            assertFalse("omitted entry must not emit a key", event.metadata.containsKey("ownerId"))
            assertEquals("kept", event.metadata["title"])
        }
    }

    @Test
    fun `container OMIT drops sensitive selections and keeps the rest`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            object : OmitConfigRepo {
                override suspend fun reveal(task: PrivateTask): Long = task.id
            }.logged(logger).reveal(PrivateTask(7L, "kept"))

            val event = kit.assertLogged(action = TaskActions.OMIT_CONFIG).single()
            assertFalse("sensitive id must not emit a key", event.metadata.containsKey("ownerId"))
            assertEquals("kept", event.metadata["title"])
        }
    }

    @Test
    fun `sensitive actor is masked by the container policy`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            object : MaskedActorRepo {
                override suspend fun act(email: String): Long = 1L
            }.logged(logger).act("user@example.com")

            val event = kit.assertLogged(action = TaskActions.ACTOR_MASKED).single()
            assertEquals(LogMasking.MASK, event.actor?.id)
        }
    }

    @Test
    fun `sensitive actor is dropped by container OMIT`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            object : OmittedActorRepo {
                override suspend fun act(email: String, title: String): Long = 1L
            }.logged(logger).act("user@example.com", "kept")

            val event = kit.assertLogged(action = TaskActions.ACTOR_OMITTED).single()
            assertNull("omitted actor must not be attributed", event.actor)
            assertEquals("kept", event.metadata["title"])
        }
    }

    @Test
    fun `type-use return annotation marks the result`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            object : ReturnSensitiveRepo {
                override fun issue(): String = "top-secret"
            }.logged(logger).issue()

            val event = kit.assertLogged(action = TaskActions.RETURN_MASKED).single()
            assertEquals(LogMasking.MASK, event.metadata["token"])
        }
    }
}
