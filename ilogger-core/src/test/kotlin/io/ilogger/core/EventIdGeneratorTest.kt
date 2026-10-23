package io.ilogger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventIdGeneratorTest {

    private val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    @Test
    fun `default generator produces RFC 4122 version 4 ids`() {
        repeat(1_000) {
            val id = UuidEventIdGenerator.next()
            assertTrue(id, uuidV4.matches(id))
        }
    }

    @Test
    fun `default generator does not repeat in a burst`() {
        val ids = HashSet<String>(10_000)
        repeat(10_000) { ids += UuidEventIdGenerator.next() }
        assertEquals(10_000, ids.size)
    }
}
