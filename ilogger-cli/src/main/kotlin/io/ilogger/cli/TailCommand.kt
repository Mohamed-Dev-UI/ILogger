package io.ilogger.cli

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Prints JSONL events as they appear, optionally following the file. */
internal object TailCommand {

    private val VALUE_OPTIONS = setOf(
        "level", "action", "entity", "actor", "category", "source",
        "since", "until", "last", "limit", "decrypt-key", "poll-millis",
    )
    private val FLAG_OPTIONS = setOf("json", "follow")

    fun execute(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val options = Options.parse(args, VALUE_OPTIONS, FLAG_OPTIONS)
        if (options.has("help")) {
            out.println(help())
            return 0
        }
        val path = Path.of(options.singleInput("tail"))
        if (!Files.isRegularFile(path)) throw CliException("file not found: $path")

        val filter = EventFilter.from(options)
        val cipher = KeyMaterial.decryptCipher(options.values("decrypt-key"))
        val limit = options.positiveInt("limit", Int.MAX_VALUE)
        val pollMillis = options.positiveInt("poll-millis", 250)
        val json = options.has("json")
        val follow = options.has("follow")

        var printed = 0
        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            var lineNumber = 0L
            while (printed < limit) {
                val raw = reader.readLine()
                if (raw == null) {
                    if (!follow) break
                    out.flush()
                    try {
                        Thread.sleep(pollMillis.toLong())
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }
                lineNumber++
                if (raw.isBlank()) continue
                val line = EventDecoder.line(raw, lineNumber, cipher)
                if (!filter.matches(line.event)) continue
                out.println(if (json) line.raw else EventFormat.compact(line.event))
                if (follow) out.flush()
                printed++
            }
        }
        return 0
    }

    fun help(): String = """
        Usage: ilogger tail <file.jsonl> [options]

        Prints events one line at a time; with --follow it keeps polling the
        file for appended events.

        Options:
          --level L[,L]       Filter by level (TRACE..ERROR, AUDIT)
          --action P[,P]      Filter by action; a trailing * is a prefix match
          --entity P          Filter by entity type
          --actor P           Filter by actor id, name or type:id
          --category P        Filter by category
          --source P          Filter by source
          --since TIME        Only events at/after TIME (epoch millis or ISO-8601)
          --until TIME        Only events at/before TIME
          --last DURATION     Only events from the last 30m, 12h, 7d, ...
          --limit N           Stop after N matching events
          --json              Print the original JSONL line instead of text
          --follow            Keep reading appended lines
          --poll-millis N     Follow polling interval (default 250)
          --decrypt-key K     Decrypt AES-GCM lines: id=base64:<key>, hex:... or file:...

        Example:
          ilogger tail var/log/audit.jsonl --level WARN,ERROR --follow
    """.trimIndent()
}
