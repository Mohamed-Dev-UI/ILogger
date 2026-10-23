package io.ilogger.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsTest {

    private fun parse(vararg args: String): Options =
        Options.parse(args.toList(), setOf("level", "output", "key"), setOf("json", "follow"))

    @Test
    fun `parses inline and separate values, repeated options and positionals`() {
        val options = parse("file.jsonl", "--level", "WARN", "--level=ERROR", "--output", "out.csv", "--json")
        assertEquals(listOf("file.jsonl"), options.positionals)
        assertEquals(listOf("WARN", "ERROR"), options.values("level"))
        assertEquals("out.csv", options.value("output"))
        assertTrue(options.has("json"))
    }

    @Test
    fun `comma lists are split and trimmed`() {
        val options = parse("--level", " warn, error ,,", "--json")
        assertEquals(listOf("warn", "error"), options.list("level"))
    }

    @Test
    fun `help is recognized without being declared`() {
        assertTrue(parse("--help").has("help"))
        assertTrue(parse("-h").has("help"))
    }

    @Test
    fun `double dash ends option parsing`() {
        val options = Options.parse(listOf("--", "--not-an-option"), setOf("level"), maxPositionals = 1)
        assertEquals(listOf("--not-an-option"), options.positionals)
    }

    @Test
    fun `unknown option is a usage error`() {
        val error = assertThrows(UsageException::class.java) { parse("--nope") }
        assertTrue(error.message!!.contains("unknown option --nope"))
    }

    @Test
    fun `missing value is a usage error`() {
        val error = assertThrows(UsageException::class.java) { parse("--level") }
        assertTrue(error.message!!.contains("requires a value"))
    }

    @Test
    fun `flag with inline value is a usage error`() {
        val error = assertThrows(UsageException::class.java) { parse("--json=yes") }
        assertTrue(error.message!!.contains("does not take a value"))
    }

    @Test
    fun `too many positionals is a usage error`() {
        val error = assertThrows(UsageException::class.java) { parse("one", "two") }
        assertTrue(error.message!!.contains("unexpected argument 'two'"))
    }

    @Test
    fun `positiveInt validates range and syntax`() {
        assertEquals(5, parse("--level", "5").positiveInt("level", 1))
        assertEquals(3, parse().positiveInt("level", 3))
        assertThrows(UsageException::class.java) { parse("--level", "0").positiveInt("level", 1) }
        assertThrows(UsageException::class.java) { parse("--level", "x").positiveInt("level", 1) }
    }

    @Test
    fun `singleInput reports the command name`() {
        val error = assertThrows(UsageException::class.java) { parse().singleInput("tail") }
        assertTrue(error.message!!.contains("tail requires an input file"))
    }
}
