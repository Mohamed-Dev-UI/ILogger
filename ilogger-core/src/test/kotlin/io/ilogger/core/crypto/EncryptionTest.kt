package io.ilogger.core.crypto

import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionTest {

    private val key = EncryptionKey("k1", ByteArray(32) { it.toByte() })
    private val key2 = EncryptionKey("k2", ByteArray(24) { (it + 1).toByte() })

    @Test
    fun `round trip for every aes key size`() {
        listOf(16, 24, 32).forEach { size ->
            val sized = EncryptionKey("k-size$size", ByteArray(size) { 7 })
            val cipher = AesGcmCipher(sized)
            val token = cipher.encrypt("payload with ünïcøde and :colons:")
            assertEquals("payload with ünïcøde and :colons:", cipher.decrypt(token))
        }
    }

    @Test
    fun `token embeds format prefix, key id and a fresh iv per record`() {
        val cipher = AesGcmCipher(key)
        val first = cipher.encrypt("same payload")
        val second = cipher.encrypt("same payload")

        assertTrue(first.startsWith("ilogenc1:k1:"))
        assertTrue(first.substringAfter("k1:").none { it == ':' })
        assertTrue("each record must use a fresh IV", first != second)
        assertEquals("same payload", cipher.decrypt(first))
        assertEquals("same payload", cipher.decrypt(second))
    }

    @Test
    fun `bit-flipping a token breaks decryption`() {
        val cipher = AesGcmCipher(key)
        val token = cipher.encrypt("audit payload")
        val tampered = StringBuilder(token).apply {
            val index = length - 5
            setCharAt(index, if (get(index) == 'A') 'B' else 'A')
        }.toString()

        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tampered) }
    }

    @Test
    fun `missing keys and wrong key material fail decryption`() {
        val token = AesGcmCipher(key).encrypt("secret")

        val withoutK1 = AesGcmCipher(key2)
        assertThrows(IllegalStateException::class.java) { withoutK1.decrypt(token) }

        val ringWithoutK1 = AesGcmCipher({ key2 }, mapOf("k2" to key2.bytes))
        assertThrows(IllegalStateException::class.java) { ringWithoutK1.decrypt(token) }

        val wrongBytes = AesGcmCipher({ key }, mapOf("k1" to ByteArray(32) { 9 }))
        assertThrows(GeneralSecurityException::class.java) { wrongBytes.decrypt(token) }
    }

    @Test
    fun `malformed tokens fail with clear errors`() {
        val cipher = AesGcmCipher(key)
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("plain text line") }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("ilogenc1:") }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("ilogenc1:noseparator") }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("ilogenc1:k1:!!!notbase64!!!") }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("ilogenc1:k1:AAAA") }
    }

    @Test
    fun `keys reject blank ids, colons and invalid sizes`() {
        assertThrows(IllegalArgumentException::class.java) { EncryptionKey("", ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { EncryptionKey("  ", ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { EncryptionKey("a:b", ByteArray(32)) }
        listOf(0, 15, 17, 33).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) { EncryptionKey("k", ByteArray(size)) }
        }
    }

    @Test
    fun `key rotation switches tokens while a keyring reads both`() {
        val current = AtomicReference(key)
        val writer = AesGcmCipher(EncryptionKeyProvider { current.get() })
        val before = writer.encrypt("before rotation")
        current.set(key2)
        val after = writer.encrypt("after rotation")

        assertTrue(before.startsWith("ilogenc1:k1:"))
        assertTrue(after.startsWith("ilogenc1:k2:"))

        val ring = AesGcmCipher({ key }, mapOf("k1" to key.bytes, "k2" to key2.bytes))
        assertEquals("before rotation", ring.decrypt(before))
        assertEquals("after rotation", ring.decrypt(after))

        val oldOnly = AesGcmCipher(key)
        assertEquals("before rotation", oldOnly.decrypt(before))
        assertThrows(IllegalStateException::class.java) { oldOnly.decrypt(after) }
    }
}
