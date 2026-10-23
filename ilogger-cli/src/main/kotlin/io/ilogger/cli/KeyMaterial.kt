package io.ilogger.cli

import io.ilogger.core.HmacTrailSigner
import io.ilogger.core.TrailSigner
import io.ilogger.core.crypto.AesGcmCipher
import io.ilogger.core.crypto.EncryptionKey
import io.ilogger.core.crypto.EncryptionKeyProvider
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Base64

/**
 * Parses `id=<material>` key specifications shared by HMAC verification and
 * AES decryption. Material never appears in logs or error messages.
 */
internal object KeyMaterial {

    private const val BASE64 = "base64:"
    private const val BASE64URL = "base64url:"
    private const val HEX = "hex:"
    private const val FILE = "file:"

    fun parse(spec: String): Pair<String, ByteArray> {
        val separator = spec.indexOf('=')
        if (separator <= 0 || separator == spec.lastIndex) {
            throw UsageException(
                "invalid key '$spec': expected id=$BASE64<value>, id=$HEX<value> or id=$FILE<path>",
            )
        }
        val id = spec.substring(0, separator).trim()
        if (id.isEmpty()) throw UsageException("invalid key '$spec': the id is empty")
        if (id.contains(':')) throw UsageException("invalid key '$spec': the id must not contain ':'")

        val material = spec.substring(separator + 1)
        val bytes = when {
            material.startsWith(BASE64) -> decodeBase64(material.removePrefix(BASE64))
            material.startsWith(BASE64URL) -> decodeBase64Url(material.removePrefix(BASE64URL))
            material.startsWith(HEX) -> decodeHex(material.removePrefix(HEX))
            material.startsWith(FILE) -> readKeyFile(material.removePrefix(FILE))
            else -> throw UsageException(
                "invalid key '$spec': material must start with $BASE64, $BASE64URL, $HEX or $FILE",
            )
        }
        if (bytes.isEmpty()) throw UsageException("invalid key '$id': material is empty")
        return id to bytes
    }

    fun hmacKeys(specs: List<String>): Map<String, TrailSigner> {
        val parsed = parseAll(specs)
        return parsed.associate { (id, bytes) -> id to HmacTrailSigner(bytes) }
    }

    /** Keyring for reading encrypted payloads; `null` when no key was given. */
    fun decryptCipher(specs: List<String>): AesGcmCipher? {
        if (specs.isEmpty()) return null
        val keys = aesKeys(specs)
        val active = keys.first()
        return AesGcmCipher(
            EncryptionKeyProvider { active },
            keys.associate { it.id to it.bytes },
        )
    }

    /** Single-key cipher for re-encrypting a replay target. */
    fun encryptCipher(spec: String): AesGcmCipher {
        val (id, bytes) = parse(spec)
        return AesGcmCipher(aesKey(id, bytes))
    }

    private fun aesKeys(specs: List<String>): List<EncryptionKey> =
        parseAll(specs).map { (id, bytes) -> aesKey(id, bytes) }

    private fun aesKey(id: String, bytes: ByteArray): EncryptionKey =
        try {
            EncryptionKey(id, bytes)
        } catch (error: IllegalArgumentException) {
            throw UsageException("invalid AES key '$id': ${error.message}")
        }

    private fun parseAll(specs: List<String>): List<Pair<String, ByteArray>> {
        val parsed = specs.map(::parse)
        val duplicate = parsed.groupBy { it.first }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) throw UsageException("duplicate key id '${duplicate.key}'")
        return parsed
    }

    private fun decodeBase64(value: String): ByteArray =
        try {
            Base64.getDecoder().decode(pad(value.trim()))
        } catch (error: IllegalArgumentException) {
            throw UsageException("invalid base64 key material: ${error.message}")
        }

    private fun decodeBase64Url(value: String): ByteArray =
        try {
            Base64.getUrlDecoder().decode(pad(value.trim()))
        } catch (error: IllegalArgumentException) {
            throw UsageException("invalid base64url key material: ${error.message}")
        }

    private fun decodeHex(value: String): ByteArray {
        val text = value.trim()
        if (text.length % 2 != 0) throw UsageException("invalid hex key material: odd number of digits")
        val bytes = ByteArray(text.length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(text[index * 2], 16)
            val low = Character.digit(text[index * 2 + 1], 16)
            if (high < 0 || low < 0) throw UsageException("invalid hex key material: non-hex digit")
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun readKeyFile(fileName: String): ByteArray {
        if (fileName.isBlank()) throw UsageException("invalid key: file path is empty")
        return try {
            Files.readAllBytes(Path.of(fileName))
        } catch (error: NoSuchFileException) {
            throw UsageException("invalid key: file '$fileName' does not exist")
        } catch (error: Exception) {
            throw UsageException("invalid key: cannot read '$fileName': ${error.message}")
        }
    }

    private fun pad(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
}
