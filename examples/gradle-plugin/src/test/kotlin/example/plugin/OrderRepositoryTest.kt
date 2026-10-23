package example.plugin

import io.ilogger.testing.TestKit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the plugin generated wrappers without any manual KSP setup. */
class OrderRepositoryTest {

    @Test
    fun `one plugin declaration instruments the repository`() = runTest {
        val kit = TestKit.create()
        kit.logger.use { logger ->
            val repository = object : OrderRepository {
                override suspend fun place(id: String, total: Int): Long = 42L
            }.logged(logger)

            assertEquals(42L, repository.place("o-1", 10))
        }

        val event = kit.assertLogged(action = OrderActions.PLACED).single()
        assertEquals("orders", event.category)
        assertEquals("order", event.entityId)
    }
}
