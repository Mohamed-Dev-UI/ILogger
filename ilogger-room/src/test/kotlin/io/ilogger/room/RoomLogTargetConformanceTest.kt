package io.ilogger.room

import io.ilogger.core.LogSink
import io.ilogger.serialization.JsonEventCodec
import io.ilogger.testing.LogSinkConformance

/** Runs the published sink contract against the Room target. */
class RoomLogTargetConformanceTest : LogSinkConformance() {

    private lateinit var dao: FakeRoomLogDao

    override fun createSink(): LogSink {
        dao = FakeRoomLogDao()
        return RoomLogTarget(codec = JsonEventCodec).also { target -> target.bind(dao) }
    }

    override fun deliveredIds(sink: LogSink): List<String> = synchronized(dao.inserted) {
        dao.inserted.map { it.eventId }
    }
}
