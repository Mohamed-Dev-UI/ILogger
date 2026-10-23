package io.ilogger.room

/**
 * Optional payload-at-rest encryption hook for [RoomLogEntity.payload].
 *
 * Writes encrypt the JSON payload before the row reaches Room
 * ([RoomLogTarget], [RoomLogs]); retention and all flat-column filters keep
 * working because metadata columns stay plaintext. Reading the payload back
 * (for example through [exportJsonLines]) requires the same cipher.
 *
 * This protects the event body only — ids, action names, actor ids and
 * timestamps remain queryable. Use SQLCipher (Room's `openHelperFactory` with
 * `SupportOpenHelperFactory`) when the whole database must be encrypted.
 *
 * Implementations must be thread-safe and deterministic: [decrypt] must return
 * exactly the plaintext [encrypt] received. There is no key management here;
 * losing the key makes existing payloads unreadable.
 */
public interface RoomLogPayloadCipher {

    /** Encrypts the codec output before it is written to the `payload` column. */
    public fun encrypt(plaintext: String): String

    /** Reverses [encrypt]; used by export helpers. */
    public fun decrypt(ciphertext: String): String
}
