package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.crypto.AesGcmCipher
import io.ilogger.core.crypto.EncryptionKey
import io.ilogger.core.sink.EventCodec
import java.security.GeneralSecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmRoomPayloadCipherTest {

    private val key = EncryptionKey("k1", ByteArray(32) { 5 })

    @Test
    fun `targets store encrypted payloads that decrypt back`() = runBlocking {
        val dao = FakeRoomLogDao()
        val cipher = AesGcmRoomPayloadCipher(AesGcmCipher(key))
        val target = RoomLogTarget(codec = EventCodec { it.id }, payloadCipher = cipher)
        target.bind(dao)

        target.writeAll(listOf(event("e1"), event("e2")))

        assertEquals(listOf("e1", "e2"), dao.inserted.map { it.eventId })
        dao.inserted.forEach { row ->
            assertTrue(row.payload, row.payload.startsWith("ilogenc1:k1:"))
            assertEquals(row.eventId, cipher.decrypt(row.payload))
        }
    }

    @Test
    fun `tampered payloads fail decryption`() {
        val cipher = AesGcmRoomPayloadCipher(AesGcmCipher(key))
        val token = cipher.encrypt("payload")
        val tampered = StringBuilder(token).apply {
            val index = length - 4
            setCharAt(index, if (get(index) == 'A') 'B' else 'A')
        }.toString()

        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tampered) }
    }

    private fun event(id: String): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1_700_000_000_000,
        level = LogLevel.AUDIT,
        action = "test.event",
    )
}
