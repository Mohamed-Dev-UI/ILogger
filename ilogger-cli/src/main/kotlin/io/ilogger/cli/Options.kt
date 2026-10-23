package io.ilogger.cli

/**
 * Minimal `--name value` / `--name=value` parser. The command declares which
 * names take a value, so flags never swallow a positional or another option.
 */
internal class Options private constructor(
    private val values: Map<String, List<String>>,
    private val flags: Set<String>,
    val positionals: List<String>,
) {

    fun has(name: String): Boolean = name in flags || name in values

    fun value(name: String): String? = values[name]?.lastOrNull()

    fun values(name: String): List<String> = values[name].orEmpty()

    /** Splits comma-separated occurrences and drops blanks. */
    fun list(name: String): List<String> = values(name)
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun positiveInt(name: String, default: Int): Int {
        val raw = value(name) ?: return default
        val parsed = raw.toIntOrNull()
            ?: throw UsageException("--$name must be a positive integer, got '$raw'")
        if (parsed <= 0) throw UsageException("--$name must be a positive integer, got '$raw'")
        return parsed
    }

    fun singleInput(command: String): String = positionals.firstOrNull()
        ?: throw UsageException("$command requires an input file; run 'ilogger help' for usage")

    companion object {

        fun parse(
            args: List<String>,
            valueOptions: Set<String>,
            flagOptions: Set<String> = emptySet(),
            maxPositionals: Int = 1,
        ): Options {
            val values = mutableMapOf<String, MutableList<String>>()
            val flags = mutableSetOf<String>()
            val positionals = mutableListOf<String>()
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                when {
                    arg == "--" -> {
                        positionals += args.drop(index + 1)
                        break
                    }
                    arg == "-h" || arg == "--help" -> flags += "help"
                    arg.startsWith("--") -> {
                        val body = arg.removePrefix("--")
                        val equals = body.indexOf('=')
                        val name = if (equals >= 0) body.substring(0, equals) else body
                        val inline = if (equals >= 0) body.substring(equals + 1) else null
                        when (name) {
                            in valueOptions -> {
                                val resolved = inline ?: args.getOrNull(index + 1)
                                    ?: throw UsageException("option --$name requires a value")
                                if (inline == null) index++
                                values.getOrPut(name) { mutableListOf() } += resolved
                            }
                            in flagOptions -> {
                                if (inline != null) {
                                    throw UsageException("option --$name does not take a value")
                                }
                                flags += name
                            }
                            else -> throw UsageException("unknown option --$name")
                        }
                    }
                    arg.length > 1 && arg.startsWith("-") -> throw UsageException("unknown option $arg")
                    else -> positionals += arg
                }
                index++
            }
            if (positionals.size > maxPositionals) {
                throw UsageException("unexpected argument '${positionals[maxPositionals]}'")
            }
            return Options(values.mapValues { it.value.toList() }, flags, positionals)
        }
    }
}
