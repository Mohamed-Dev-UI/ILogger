package io.ilogger.cli

import io.ilogger.core.LogEvent
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Exports JSONL or a Room database to CSV, a JSON array or JSONL. */
internal object ExportCommand {

    private val VALUE_OPTIONS = setOf(
        "from", "format", "output", "table",
        "level", "action", "entity", "actor", "category", "source",
        "since", "until", "last", "limit", "decrypt-key",
    )

    fun execute(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val options = Options.parse(args, VALUE_OPTIONS)
        if (options.has("help")) {
            out.println(help())
            return 0
        }
        val input = Path.of(options.singleInput("export"))
        if (!Files.isRegularFile(input)) throw CliException("file not found: $input")

        val from = (options.value("from") ?: "auto").lowercase()
        val room = when (from) {
            "auto" -> RoomSource.looksLikeDatabase(input)
            "room" -> true
            "jsonl" -> false
            else -> throw UsageException("--from must be auto, room or jsonl")
        }
        if (!room && options.value("table") != null) {
            throw UsageException("--table only applies to Room input")
        }
        val table = options.value("table") ?: RoomSource.DEFAULT_TABLE

        val format = (options.value("format") ?: "csv").lowercase()
        if (format !in setOf("csv", "json", "jsonl")) {
            throw UsageException("--format must be csv, json or jsonl")
        }
        val cipher = KeyMaterial.decryptCipher(options.values("decrypt-key"))
        val filter = EventFilter.from(options)
        val limit = options.positiveInt("limit", Int.MAX_VALUE)

        val outputPath = options.value("output")?.takeIf { it != "-" }?.let(Path::of)
        val writer = if (outputPath == null) {
            out
        } else {
            outputPath.toAbsolutePath().parent?.let(Files::createDirectories)
            PrintStream(Files.newOutputStream(outputPath), true, Charsets.UTF_8)
        }

        var written = 0
        var firstJson = true
        try {
            if (format == "csv") writer.println(EventFormat.csvHeader)
            if (format == "json") writer.print("[")

            val emit: (LogEvent) -> Boolean = { event ->
                if (!filter.matches(event)) {
                    true
                } else {
                    when (format) {
                        "csv" -> writer.println(EventFormat.csv(event))
                        "jsonl" -> writer.println(EventFormat.jsonLine(event))
                        else -> {
                            if (!firstJson) writer.print(",")
                            writer.print("\n  ")
                            writer.print(EventFormat.jsonLine(event))
                            firstJson = false
                        }
                    }
                    written++
                    written < limit
                }
            }

            if (room) {
                RoomSource.read(input, table, cipher, emit)
            } else {
                Files.newBufferedReader(input, Charsets.UTF_8).use { reader ->
                    var lineNumber = 0L
                    while (true) {
                        val raw = reader.readLine() ?: break
                        lineNumber++
                        if (raw.isBlank()) continue
                        val line = EventDecoder.line(raw, lineNumber, cipher)
                        if (!emit(line.event)) break
                    }
                }
            }

            if (format == "json") {
                writer.print(if (firstJson) "]\n" else "\n]\n")
            }
            writer.flush()
        } finally {
            if (writer !== out) writer.close()
        }

        val destination = outputPath?.toString() ?: "stdout"
        err.println("exported $written events ($format) to $destination")
        return 0
    }

    fun help(): String = """
        Usage: ilogger export <input.jsonl|database.db> [options]

        Converts shipped events to CSV, a JSON array or canonical JSONL. Room
        databases are read directly; the payload column must contain JSON (an
        encryption keyring can be supplied with --decrypt-key).

        Options:
          --from auto|room|jsonl   Input type (default: auto by file extension)
          --format csv|json|jsonl  Output format (default: csv)
          --output PATH            Write to PATH instead of stdout ('-' = stdout)
          --table NAME             Room table (default: ilogger_events)
          --level/--action/...     Same filters as tail
          --since/--until/--last   Time window filters
          --limit N                Export at most N matching events
          --decrypt-key K          AES-GCM keyring for encrypted payloads

        Examples:
          ilogger export app.db --format csv --output audit.csv
          ilogger export audit.jsonl --action 'task.*' --format json
          ilogger export app.db --decrypt-key prod=file:/run/secrets/audit-aes.key
    """.trimIndent()
}
