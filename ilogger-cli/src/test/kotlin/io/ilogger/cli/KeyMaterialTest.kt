package io.ilogger.cli

import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyMaterialTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bytes = ByteArray(32) { it.toByte() }

    @Test
    fun `parses standard, unpadded, url-safe, hex and file material`() {
        val standard = Base64.getEncoder().encodeToString(bytes)
        assertArrayEquals(bytes, KeyMaterial.parse("prod=base64:$standard").second)
        assertArrayEquals(bytes, KeyMaterial.parse("prod=base64:${standard.trimEnd('=')}").second)

        val url = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertArrayEquals(bytes, KeyMaterial.parse("prod=base64url:$url").second)

        val hex = bytes.joinToString("") { "%02x".format(it) }
        assertArrayEquals(bytes, KeyMaterial.parse("prod=hex:$hex").second)
        assertArrayEquals(bytes, KeyMaterial.parse("prod=hex:${hex.uppercase()}").second)

        val file = folder.newFile("audit.key")
        Files.write(file.toPath(), bytes)
        assertArrayEquals(bytes, KeyMaterial.parse("prod=file:${file.absolutePath}").second)
    }

    @Test
    fun `hmac keys accept rotation ids and reject duplicates`() {
        val keys = KeyMaterial.hmacKeys(
            listOf("old=hex:${"00".repeat(32)}", "new=hex:${"11".repeat(32)}"),
        )
        assertEquals(setOf("old", "new"), keys.keys)
        assertTrue(keys.getValue("old").sign(byteArrayOf(1)).isNotEmpty())

        val duplicate = assertThrows(UsageException::class.java) {
            KeyMaterial.hmacKeys(listOf("same=hex:00", "same=hex:11"))
        }
        assertTrue(duplicate.message!!.contains("duplicate key id"))
    }

    @Test
    fun `decrypt cipher is null without keys and round-trips with one`() {
        assertNull(KeyMaterial.decryptCipher(emptyList()))

        val hex = bytes.copyOf(16).joinToString("") { "%02x".format(it) }
        val encrypt = KeyMaterial.encryptCipher("prod=hex:$hex")
        val token = encrypt.encrypt("hello audit")
        assertTrue(token.startsWith("ilogenc1:prod:"))

        val decrypt = KeyMaterial.decryptCipher(listOf("prod=hex:$hex"))
        assertEquals("hello audit", decrypt!!.decrypt(token))
    }

    @Test
    fun `invalid specifications are usage errors`() {
        val cases = listOf(
            "missing-separator",
            "=base64:AAAA",
            "id=raw:AAAA",
            "id=hex:abc",
            "id=hex:zz",
            "id=base64:!!!",
            "id=file:/definitely/not/here.key",
            "id=base64:",
        )
        for (spec in cases) {
            val error = assertThrows("spec: $spec", UsageException::class.java) {
                KeyMaterial.parse(spec)
            }
            assertTrue("spec $spec: ${error.message}", error.message!!.isNotEmpty())
        }
    }

    @Test
    fun `AES keys must be 16, 24 or 32 bytes`() {
        val error = assertThrows(UsageException::class.java) {
            KeyMaterial.decryptCipher(listOf("prod=hex:0011"))
        }
        assertTrue(error.message!!.contains("16, 24 or 32 bytes"))
    }
}
