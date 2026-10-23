package io.ilogger.cli

import io.ilogger.core.LogEvent
import io.ilogger.core.crypto.TextCipher
import io.ilogger.serialization.JsonEventCodec

/** One non-blank JSONL line, decoded and tagged with its 1-based line number. */
internal data class EventLine(val number: Long, val raw: String, val event: LogEvent)

internal object EventDecoder {

    private const val ENCRYPTED_PREFIX = "ilogenc1:"

    fun decode(raw: String, location: String, cipher: TextCipher?): LogEvent {
        val plaintext = if (raw.startsWith(ENCRYPTED_PREFIX)) {
            val active = cipher ?: throw CliException(
                "$location is encrypted; pass --decrypt-key id=base64:<key>",
            )
            try {
                active.decrypt(raw)
            } catch (error: Exception) {
                throw CliException("$location cannot be decrypted: ${error.message}", error)
            }
        } else {
            raw
        }
        return try {
            JsonEventCodec.decode(plaintext)
        } catch (error: Exception) {
            throw CliException("$location is not a JSON ILogger event: ${error.message}", error)
        }
    }

    fun line(raw: String, number: Long, cipher: TextCipher?): EventLine =
        EventLine(number, raw, decode(raw, "line $number", cipher))
}
