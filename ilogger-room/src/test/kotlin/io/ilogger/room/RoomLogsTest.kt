package io.ilogger.room

import io.ilogger.core.ILogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomLogsTest {

    @Test
    fun `roomLogs persists buffered events through the dao`() = runBlocking {
        val dao = FakeRoomLogDao()
        val logger = ILogger.create {
            batchSize = 4
            flushIntervalMillis = 1
            roomLogs(dao)
        }

        repeat(6) { logger.recordNow(action = "event.$it") }
        logger.close()

        assertEquals(6, dao.inserted.size)
        assertEquals(
            (0..5).map { "event.$it" },
            dao.inserted.map { it.action },
        )
    }
}
