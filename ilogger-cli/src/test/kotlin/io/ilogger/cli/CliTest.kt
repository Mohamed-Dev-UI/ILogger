package io.ilogger.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CliTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `no arguments prints usage and fails with exit code 2`() {
        val result = runCli()

        assertEquals(2, result.code)
        assertTrue(result.stdout.contains("ILogger audit tooling"))
        assertTrue(result.stdout.contains("Exit codes: 0 success, 1 failure"))
    }

    @Test
    fun `help prints the command list`() {
        val result = runCli("help")

        assertEquals(0, result.code)
        listOf("tail", "verify", "export", "schema", "replay").forEach { command ->
            assertTrue("missing $command", result.stdout.contains(command))
        }
    }

    @Test
    fun `top-level help flags succeed`() {
        assertEquals(0, runCli("--help").code)
        assertEquals(0, runCli("-h").code)
    }

    @Test
    fun `every command has its own help`() {
        val commands = mapOf(
            "tail" to "Usage: ilogger tail",
            "verify" to "Usage: ilogger verify",
            "export" to "Usage: ilogger export",
            "schema" to "Usage: ilogger schema",
            "replay" to "Usage: ilogger replay",
        )
        commands.forEach { (command, expected) ->
            val result = runCli(command, "--help")
            assertEquals(command, 0, result.code)
            assertTrue(command, result.stdout.contains(expected))
        }
    }

    @Test
    fun `unknown commands are usage errors`() {
        val result = runCli("inspect")

        assertEquals(2, result.code)
        assertTrue(result.stderr.contains("unknown command 'inspect'"))
        assertTrue(result.stderr.contains("Run 'ilogger help' for usage."))
    }

    @Test
    fun `invalid options report the usage hint`() {
        val path = folder.newFile("audit.jsonl").toPath()
        val limit = runCli("tail", path.toString(), "--limit", "0")
        assertEquals(2, limit.code)
        assertTrue(limit.stderr, limit.stderr.contains("--limit must be a positive integer"))
        assertTrue(limit.stderr, limit.stderr.contains("Run 'ilogger help' for usage."))

        val unknown = runCli("tail", path.toString(), "--verbose")
        assertEquals(2, unknown.code)
        assertTrue(unknown.stderr, unknown.stderr.contains("unknown option --verbose"))
    }

    @Test
    fun `runtime failures do not print the usage hint`() {
        val result = runCli("tail", folder.root.resolve("missing.jsonl").toString())

        assertEquals(1, result.code)
        assertFalse(result.stderr.contains("Run 'ilogger help' for usage."))
    }

    @Test
    fun `options accept both separate and inline values`() {
        val path = writeJsonl(folder.newFile("audit.jsonl").toPath(), listOf(sampleEvent()))

        val separate = runCli("tail", path.toString(), "--limit", "1")
        val inline = runCli("tail", path.toString(), "--limit=1")

        assertEquals(0, separate.code)
        assertEquals(separate.stdout, inline.stdout)
    }
}
