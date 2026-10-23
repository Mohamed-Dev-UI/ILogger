package io.ilogger.cli

import java.io.PrintStream

internal object Cli {

    fun run(args: Array<out String>, out: PrintStream, err: PrintStream): Int = try {
        when (args.firstOrNull()) {
            null -> {
                out.println(usage())
                2
            }
            "help", "--help", "-h" -> {
                out.println(usage())
                0
            }
            "tail" -> TailCommand.execute(args.drop(1), out, err)
            "verify" -> VerifyCommand.execute(args.drop(1), out, err)
            "export" -> ExportCommand.execute(args.drop(1), out, err)
            "schema" -> SchemaCommand.execute(args.drop(1), out, err)
            "replay" -> ReplayCommand.execute(args.drop(1), out, err)
            else -> throw UsageException("unknown command '${args.first()}'")
        }
    } catch (error: UsageException) {
        err.println("error: ${error.message}")
        err.println("Run 'ilogger help' for usage.")
        2
    } catch (error: CliException) {
        err.println("error: ${error.message}")
        1
    } catch (error: Exception) {
        err.println("error: ${error.message ?: error::class.java.name}")
        1
    }

    fun usage(): String = """
        ILogger audit tooling

        Usage: ilogger <command> [options]

        Commands:
          tail <file.jsonl>       Print matching events (optionally --follow)
          verify <file.jsonl>     Verify the tamper-evident hash chain
          export <input>          Convert JSONL or a Room database to CSV/JSON/JSONL
          schema --manifest FILE  Inspect the generated action registry
          replay <file.jsonl>     Re-emit events into console or a JSONL file
          help                    Show this help (also: <command> --help)

        Exit codes: 0 success, 1 failure (including a broken chain), 2 usage error.

        Examples:
          ilogger tail var/log/audit.jsonl --level WARN,ERROR --follow
          ilogger verify audit.jsonl --hmac-key prod=file:/run/secrets/audit.key
          ilogger verify audit.jsonl --json
          ilogger export app.db --format csv --output audit.csv
          ilogger export audit.jsonl --action 'task.*' --format json
          ilogger schema --manifest build/ilogger/schema.json
          ilogger replay audit.jsonl --to jsonl:replayed.jsonl --action task.created

        Encrypted payloads: pass --decrypt-key id=base64:<key> (or hex:/file:) to
        tail, verify, export and replay. Replay with --to jsonl:<path> plus
        --encrypt-key rewrites a file under a new AES-GCM key.
    """.trimIndent()
}
