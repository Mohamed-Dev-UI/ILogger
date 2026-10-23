package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.FixedClock
import io.ilogger.core.LogEvent
import io.ilogger.core.sink.EventCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun event(id: String, action: String = id): LogEvent = LogEvent(
    id = id,
    timestampMillis = 1_700_000_000_000,
    level = LogLevel.AUDIT,
    action = action,
)

class RoomLogTargetTest {

    @Test
    fun `write waits until the dao is bound`() = runBlocking {
        val dao = FakeRoomLogDao()
        val target = RoomLogTarget(EventCodec { "p" })

        val write = async(Dispatchers.Default) { target.writeAll(listOf(event("e1"))) }
        delay(50)
        assertTrue(dao.inserted.isEmpty())

        target.bind(dao)
        write.await()

        assertEquals(listOf("e1"), dao.inserted.map { it.eventId })
    }

    @Test
    fun `write-triggered retention honors legal holds`() = runBlocking {
        val dao = FakeRoomLogDao()
        val holds = RoomLogLegalHoldRegistry().apply { hold(RoomLogLegalHold.Actor("user-1")) }
        val target = RoomLogTarget(
            codec = EventCodec { "p" },
            retention = RoomLogRetention(keepMillis = 5_000, sweepEveryMillis = 1_000),
            clock = FixedClock(1_700_000_010_000),
            holds = holds,
        )
        target.bind(dao)

        target.writeAll(
            listOf(
                event("held").copy(actor = Actor.user("user-1")),
                event("other"),
            ),
        )

        assertEquals(listOf("held"), dao.inserted.map { it.eventId })
        assertTrue("hold-aware sweep does not call purgeBefore", dao.purged.isEmpty())
    }

    @Test
    fun `retention sweeps on first write then at most once per interval`() = runBlocking {
        val clock = FixedClock(1_000_000_000)
        val dao = FakeRoomLogDao()
        val target = RoomLogTarget(
            codec = EventCodec { "p" },
            retention = RoomLogRetention(keepMillis = 86_400_000, sweepEveryMillis = 3_600_000),
            clock = clock,
        )
        target.bind(dao)

        target.writeAll(listOf(event("e1")))
        assertEquals(listOf(1_000_000_000L - 86_400_000), dao.purged)

        clock.set(clock.nowMillis() + 60_000)
        target.writeAll(listOf(event("e2")))
        assertEquals(1, dao.purged.size)

        clock.set(clock.nowMillis() + 3_600_000)
        target.writeAll(listOf(event("e3")))
        assertEquals(2, dao.purged.size)
    }

    @Test
    fun `purge failure does not fail the write`() = runBlocking {
        val errors = mutableListOf<Throwable>()
        val dao = FakeRoomLogDao()
        val target = RoomLogTarget(
            codec = EventCodec { "p" },
            retention = RoomLogRetention(keepMillis = 1_000, sweepEveryMillis = 1_000),
            clock = FixedClock(10_000),
            onError = { errors.add(it) },
        )
        dao.purgeError = IllegalStateException("database locked")
        target.bind(dao)

        target.writeAll(listOf(event("e1")))

        assertEquals(1, dao.inserted.size)
        assertEquals(1, errors.size)
    }

    @Test
    fun `throwing purge error handler does not fail the write`() = runBlocking {
        val dao = FakeRoomLogDao()
        val target = RoomLogTarget(
            codec = EventCodec { "p" },
            retention = RoomLogRetention(keepMillis = 1, sweepEveryMillis = 1),
            clock = FixedClock(10),
            onError = { throw IllegalStateException("handler failed") },
        )
        dao.purgeError = IllegalStateException("database locked")
        target.bind(dao)

        target.writeAll(listOf(event("e1")))

        assertEquals(1, dao.inserted.size)
    }

    @Test
    fun `no retention policy never purges`() = runBlocking {
        val dao = FakeRoomLogDao()
        val target = RoomLogTarget(EventCodec { "p" })
        target.bind(dao)

        target.writeAll(listOf(event("e1")))

        assertTrue(dao.purged.isEmpty())
    }

    @Test
    fun `empty batch never touches the dao`() = runBlocking {
        val dao = FakeRoomLogDao()
        val target = RoomLogTarget(EventCodec { "p" })
        target.bind(dao)

        target.writeAll(emptyList())

        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun `close after bind is a no-op`() {
        val target = RoomLogTarget(EventCodec { "p" })
        target.bind(FakeRoomLogDao())
        target.close()
    }

    @Test
    fun `close before bind fails clearly`() {
        val target = RoomLogTarget(EventCodec { "p" })

        val failure = org.junit.Assert.assertThrows(RoomLogTargetUnboundException::class.java) {
            target.close()
        }

        assertTrue(failure.message!!.contains("bind"))
    }

    @Test
    fun `write fails after the bind timeout`() {
        val target = RoomLogTarget(EventCodec { "p" }, bindTimeoutMillis = 50)

        val failure = runCatching {
            runBlocking { target.writeAll(listOf(event("e1"))) }
        }.exceptionOrNull()

        assertTrue(failure is RoomLogTargetUnboundException)
        assertTrue(failure!!.message!!.contains("50ms"))
    }

    @Test
    fun `write succeeds when bound within the timeout`() = runBlocking {
        val dao = FakeRoomLogDao()
        val target = RoomLogTarget(EventCodec { "p" }, bindTimeoutMillis = 5_000)

        val write = async(Dispatchers.Default) { target.writeAll(listOf(event("e1"))) }
        delay(50)
        target.bind(dao)
        write.await()

        assertEquals(listOf("e1"), dao.inserted.map { it.eventId })
    }

    @Test
    fun `negative bind timeout is rejected`() {
        val failure = runCatching { RoomLogTarget(EventCodec { "p" }, bindTimeoutMillis = -1) }
            .exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `payload cipher encrypts the stored payload`() = runBlocking {
        val dao = FakeRoomLogDao()
        val cipher = object : RoomLogPayloadCipher {
            override fun encrypt(plaintext: String): String = "enc:$plaintext"

            override fun decrypt(ciphertext: String): String = ciphertext.removePrefix("enc:")
        }
        val target = RoomLogTarget(codec = EventCodec { "plain" }, payloadCipher = cipher)
        target.bind(dao)

        target.writeAll(listOf(event("e1")))

        assertEquals("enc:plain", dao.inserted.single().payload)
    }
}
