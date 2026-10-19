package diag.check

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the processor's diagnostics against the `// EXPECTED:` markers
 * declared in the fixture sources. The fixtures compile in
 * `ilogger.expect-diagnostics` (fixture) mode: every invalid `@Log` usage
 * becomes a warning plus a line in:
 *
 *   build/generated/ksp/main/kotlin/ilogger-diagnostics.txt
 *
 * The `kspKotlin` task runs as part of the main compilation chain, so the
 * report file is current whenever tests run.
 */
class DiagnosticsReportTest {

    private val reportFile: File =
        // .txt outputs from the code generator land in the resources dir
        File("build/generated/ksp/main/resources/ilogger-diagnostics.txt")

    private val report: List<String> by lazy {
        assertTrue(
            "Expected the diagnostics report to exist; run :ksp-diagnostics:kspKotlin first.",
            reportFile.isFile,
        )
        reportFile.readLines().map { it.trim() }.filter { it.isNotBlank() }
    }

    @Test
    fun `all six fixtures are captured`() {
        assertTrue(
            "expected at least 6 recorded diagnostics, got ${report.size}: $report",
            report.size >= 6,
        )
    }

    @Test
    fun `every declared marker appears in the report`() {
        val markers = File("src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { source ->
                source.readLines().mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("// EXPECTED:")) {
                        trimmed.removePrefix("// EXPECTED:").trim()
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue("fixtures must declare EXPECTED markers; found ${markers.size}", markers.size >= 6)

        for (expected in markers) {
            assertTrue(
                "diagnostics report is missing marker:\n  '$expected'\nreport:\n${report.joinToString("\n")}",
                report.any { it.contains(expected, ignoreCase = true) },
            )
        }
    }
}
