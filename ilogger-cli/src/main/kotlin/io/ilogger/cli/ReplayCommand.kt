package io.ilogger.cli

import io.ilogger.core.LogSink
import io.ilogger.core.sink.ConsoleLogSink
import io.ilogger.core.sink.EventCodec
import io.ilogger.core.sink.TextFileSink
import io.ilogger.serialization.JsonEventCodec
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Re-emits filtered events directly into a sink, preserving ids and timestamps. */
internal object ReplayCommand {

    private val VALUE_OPTIONS = setOf(
        "to", "level", "action", "entity", "actor", "category", "source",
        "since", "until", "last", "limit", "decrypt-key", "encrypt-key",
    )
    private val FLAG_OPTIONS = setOf("json", "overwrite", "quiet")

    fun execute(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val options = Options.parse(args, VALUE_OPTIONS, FLAG_OPTIONS)
        if (options.has("help")) {
            out.println(help())
            return 0
        }
        val input = Path.of(options.singleInput("replay"))
        if (!Files.isRegularFile(input)) throw CliException("file not found: $input")

        val target = options.value("to") ?: "console"
        val filter = EventFilter.from(options)
        val limit = options.positiveInt("limit", Int.MAX_VALUE)
        val cipher = KeyMaterial.decryptCipher(options.values("decrypt-key"))
        val overwrite = options.has("overwrite")
        val quiet = options.has("quiet")

        val sink: LogSink
        val label: String
        if (target == "console") {
            if (overwrite) throw UsageException("--overwrite only applies to a jsonl: target")
            if (options.value("encrypt-key") != null) {
                throw UsageException("--encrypt-key only applies to a jsonl: target")
            }
            val codec = if (options.has("json")) {
                JsonEventCodec
            } else {
                EventCodec { event -> EventFormat.compact(event) }
            }
            sink = ConsoleLogSink(codec, out::println)
            label = "console"
        } else if (target.startsWith("jsonl:")) {
            val fileName = target.removePrefix("jsonl:")
            if (fileName.isBlank()) throw UsageException("--to jsonl:<path> requires a path")
            val encrypt = options.value("encrypt-key")?.let(KeyMaterial::encryptCipher)
            sink = TextFileSink(Path.of(fileName), JsonEventCodec, append = !overwrite, cipher = encrypt)
            label = fileName
        } else {
            throw UsageException("--to must be 'console' or 'jsonl:<path>'")
        }

        var written = 0
        try {
            Files.newBufferedReader(input, Charsets.UTF_8).use { reader ->
                var lineNumber = 0L
                while (written < limit) {
                    val raw = reader.readLine() ?: break
                    lineNumber++
                    if (raw.isBlank()) continue
                    val line = EventDecoder.line(raw, lineNumber, cipher)
                    if (!filter.matches(line.event)) continue
                    runBlocking { sink.write(line.event) }
                    written++
                }
            }
            runBlocking { sink.flush() }
        } finally {
            sink.close()
        }

        if (!quiet) err.println("replayed $written events to $label")
        return 0
    }

    fun help(): String = """
        Usage: ilogger replay <file.jsonl> [options]

        Re-emits matching events into a sink. Events bypass the engine, so ids,
        timestamps and existing data are preserved; use export to convert
        formats instead.

        Targets:
          console            One compact text line per event (default)
          jsonl:<path>       Append canonical JSONL to <path>

        Options:
          --to TARGET        console or jsonl:<path>
          --json             Console output as JSONL
          --overwrite        Truncate a jsonl: target instead of appending
          --encrypt-key K    AES-GCM key for a jsonl: target (id=base64/hex/file)
          --decrypt-key K    AES-GCM keyring for encrypted input (repeatable)
          --level/--action/...  Same filters as tail
          --since/--until/--last
          --limit N          Replay at most N matching events
          --quiet            Do not print the summary

        Examples:
          ilogger replay audit.jsonl --to jsonl:replayed.jsonl --action task.created
          ilogger replay audit.jsonl --decrypt-key old=... \
              --to jsonl:rotated.jsonl --encrypt-key new=... --overwrite
    """.trimIndent()
}
