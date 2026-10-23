package io.ilogger.core.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One key of the at-rest encryption format: a stable [id] plus raw AES key
 * bytes (16, 24 or 32 bytes for AES-128/192/256).
 *
 * The [id] is stored next to every ciphertext so verifiers and readers with a
 * keyring can pick the right key across rotations; it must not contain `:`
 * (the token delimiter). Key bytes should come from a secret store, never be
 * committed, and never be derived from constants shipped in the binary.
 * Losing every key of an id makes the payloads written under it permanently
 * unreadable — encryption deliberately has no recovery path.
 */
public class EncryptionKey(public val id: String, public val bytes: ByteArray) {
    init {
        require(id.isNotBlank()) { "EncryptionKey id must not be blank" }
        require(!id.contains(':')) { "EncryptionKey id must not contain ':' (token delimiter)" }
        require(bytes.size == 16 || bytes.size == 24 || bytes.size == 32) {
            "EncryptionKey bytes must be 16, 24 or 32 bytes (AES-128/192/256), got ${bytes.size}"
        }
    }
}

/**
 * Supplies the key used for **new** writes. Called on every encrypted record,
 * so implementations must be thread-safe and cheap; returning a new
 * [EncryptionKey] id at any point rotates the stream — records keep the id of
 * the key that encrypted them, and readers hold a keyring of old ids until the
 * records stamped with them are gone.
 */
public fun interface EncryptionKeyProvider {
    public fun currentKey(): EncryptionKey
}

/**
 * Bidirectional text cipher for payloads at rest (file-sink lines, Room
 * payload columns). [encrypt] must embed everything a reader needs — format
 * version, key id, IV — inside the returned token; [decrypt] reverses it.
 *
 * Implementations must be thread-safe. Verification of integrity is part of
 * the cipher contract: decrypting a modified token must fail rather than
 * return attacker-controlled plaintext.
 */
public interface TextCipher {
    public fun encrypt(plaintext: String): String
    public fun decrypt(token: String): String
}

/**
 * AES-GCM ([javax.crypto.Cipher] `AES/GCM/NoPadding`) text cipher.
 *
 * Token format, one ASCII line per payload:
 *
 * ```
 * ilogenc1:<keyId>:<base64url(12-byte IV ‖ ciphertext ‖ 16-byte GCM tag)>
 * ```
 *
 * - A fresh random 12-byte IV per record (birthday-bound safe far beyond any
 *   realistic audit volume for one key);
 * - the GCM tag authenticates IV + ciphertext + the embedded key id, so
 *   truncating, reordering (across keys), bit-flipping or splicing tokens
 *   fails [decrypt] with a `AEADBadTagException`;
 * - the key id travels in the token, so [keyProvider] can rotate keys at any
 *   moment and a reader holding a keyring verifies across the rotation.
 *
 * Constructs a [Cipher] per call — thread-safe by design; the file sink and
 * Room paths call this from their IO workers only.
 *
 * @param keyProvider source of the key for new writes; fetched per record.
 * @param keysForReading keyring for [decrypt], keyed by [EncryptionKey.id].
 *   Pass every key that may appear in the data being read.
 */
public class AesGcmCipher(
    private val keyProvider: EncryptionKeyProvider,
    private val keysForReading: Map<String, ByteArray> = emptyMap(),
) : TextCipher {

    /** Convenience constructor for deployments that never rotate keys. */
    public constructor(key: EncryptionKey) : this({ key }, mapOf(key.id to key.bytes))

    override fun encrypt(plaintext: String): String {
        val key = keyProvider.currentKey()
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val gcm = Cipher.getInstance(TRANSFORM)
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val sealed = gcm.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(iv + sealed)
        return "$FORMAT_PREFIX${key.id}:$payload"
    }

    override fun decrypt(token: String): String {
        if (!token.startsWith(FORMAT_PREFIX)) {
            throw IllegalArgumentException(
                "ilogger crypto: token is not an encrypted payload (missing '$FORMAT_PREFIX' prefix)",
            )
        }
        val withoutPrefix = token.substring(FORMAT_PREFIX.length)
        val separator = withoutPrefix.indexOf(':')
        if (separator <= 0) {
            throw IllegalArgumentException("ilogger crypto: token is missing the key id")
        }
        val keyId = withoutPrefix.substring(0, separator)
        val keyBytes = keysForReading[keyId]
            ?: throw IllegalStateException("ilogger crypto: no key for id '$keyId'")
        val payload = try {
            Base64.getUrlDecoder().decode(withoutPrefix.substring(separator + 1))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("ilogger crypto: token payload is not base64url")
        }
        if (payload.size <= IV_BYTES) {
            throw IllegalArgumentException("ilogger crypto: token payload is too short")
        }
        val gcm = Cipher.getInstance(TRANSFORM)
        gcm.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES),
        )
        return String(gcm.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        const val FORMAT_PREFIX: String = "ilogenc1:"

        /** Shared, thread-safe source of per-record IVs. */
        val random = SecureRandom()
    }
}
