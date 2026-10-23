package io.ilogger.cli

import io.ilogger.core.LogEvent
import io.ilogger.core.TrailVerifier
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Verifies a hash-chained JSONL file, keyed or structural-only. */
internal object VerifyCommand {

    private val VALUE_OPTIONS = setOf("hmac-key", "decrypt-key")
    private val FLAG_OPTIONS = setOf("json")

    fun execute(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val options = Options.parse(args, VALUE_OPTIONS, FLAG_OPTIONS)
        if (options.has("help")) {
            out.println(help())
            return 0
        }
        val path = Path.of(options.singleInput("verify"))
        if (!Files.isRegularFile(path)) throw CliException("file not found: $path")

        val cipher = KeyMaterial.decryptCipher(options.values("decrypt-key"))
        val keys = KeyMaterial.hmacKeys(options.values("hmac-key"))
        val events = ArrayList<LogEvent>()
        val lineNumbers = ArrayList<Long>()

        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            var lineNumber = 0L
            while (true) {
                val raw = reader.readLine() ?: break
                lineNumber++
                if (raw.isBlank()) continue
                val line = EventDecoder.line(raw, lineNumber, cipher)
                events += line.event
                lineNumbers += lineNumber
            }
        }

        val verification = TrailVerifier.verify(events, keys)
        val keyed = keys.isNotEmpty()

        if (options.has("json")) {
            val payload = buildJsonObject {
                put("valid", verification.valid)
                put("checked", verification.checked)
                put("total", events.size)
                put("mode", if (keyed) "keyed" else "structural")
                if (keyed) {
                    put(
                        "keys",
                        buildJsonArray {
                            keys.keys.sorted().forEach { keyId -> add(JsonPrimitive(keyId)) }
                        },
                    )
                }
                if (!verification.valid) {
                    put("brokenAt", verification.brokenAt ?: -1)
                    put("line", verification.brokenAt?.let { lineNumbers.getOrNull(it) } ?: -1)
                    put("reason", verification.reason ?: "")
                }
            }
            out.println(payload)
        } else if (verification.valid) {
            val mode = if (keyed) {
                "keyed: ${keys.keys.sorted().joinToString(", ")}"
            } else {
                "structural only; pass --hmac-key to authenticate content"
            }
            out.println("OK ${verification.checked} events verified ($mode)")
        } else {
            val line = verification.brokenAt?.let { lineNumbers.getOrNull(it) }
            out.println("FAIL line ${line ?: "?"}: ${verification.reason}")
        }

        return if (verification.valid) 0 else 1
    }

    fun help(): String = """
        Usage: ilogger verify <file.jsonl> [options]

        Recomputes the tamper-evident hash chain. Without --hmac-key only the
        chain structure is checked (stamps present, sequence and links
        consistent); content tampering is detectable only in keyed mode.

        Options:
          --hmac-key K       id=base64:<key>, id=hex:<key> or id=file:<path>;
                             repeat for every key id in the file
          --decrypt-key K    AES-GCM keyring for encrypted lines (repeatable)
          --json             Machine-readable verdict

        Exit codes: 0 verified, 1 broken or unreadable, 2 usage error.

        Examples:
          ilogger verify audit.jsonl --hmac-key prod=file:/run/secrets/audit.key
          ilogger verify audit.jsonl --hmac-key old=... --hmac-key new=...
          ilogger verify audit.jsonl --json
    """.trimIndent()
}
