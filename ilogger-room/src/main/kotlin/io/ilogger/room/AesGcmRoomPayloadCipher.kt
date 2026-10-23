package io.ilogger.room

import io.ilogger.core.crypto.AesGcmCipher

/**
 * [RoomLogPayloadCipher] backed by [AesGcmCipher]: every payload column is
 * stored as one self-describing AES-GCM token
 * (`ilogenc1:<keyId>:<base64url(IV ‖ ciphertext ‖ tag)>`), so tampering with a
 * stored row fails [decrypt] with `AEADBadTagException` and keys can rotate
 * between rows — the key id travels inside each token, and export/import
 * helpers decrypt with a keyring of old and new keys.
 *
 * ```kotlin
 * val cipher = AesGcmRoomPayloadCipher(
 *     AesGcmCipher(EncryptionKey("2026-q4", keyBytesFromYourSecretStore)),
 * )
 * RoomLogTarget(database.logDao(), payloadCipher = cipher)
 * ```
 *
 * This protects the event **body** only — ids, action names, actor ids and
 * timestamps stay queryable. When the whole database must be encrypted, use
 * SQLCipher instead (`Room.databaseBuilder(...).openHelperFactory(
 * SupportOpenHelperFactory(passphrase))`); the two combine safely.
 *
 * Key loss makes existing payloads permanently unreadable: retention keeps
 * working (flat columns are plaintext), but exported payloads cannot be
 * decrypted without the keyring. Never ship default keys.
 */
public class AesGcmRoomPayloadCipher(private val cipher: AesGcmCipher) : RoomLogPayloadCipher {
    override fun encrypt(plaintext: String): String = cipher.encrypt(plaintext)

    override fun decrypt(ciphertext: String): String = cipher.decrypt(ciphertext)
}
