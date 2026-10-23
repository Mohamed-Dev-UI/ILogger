package diagwarn

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the G-26 warning families against the processor's fixture-mode
 * report, including the negative cases: suppressed and compliant fixtures
 * must stay out of the report entirely.
 */
class WarningsReportTest {

    private val reportFile: File =
        File("build/generated/ksp/main/resources/ilogger-diagnostics.txt")

    private val report: List<String> by lazy {
        assertTrue(
            "Expected the diagnostics report to exist; run :ksp-diagnostics-warnings:kspKotlin first.",
            reportFile.isFile,
        )
        reportFile.readLines().map { it.trim() }.filter { it.isNotBlank() }
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

    @Test
    fun `suppressed diagnostics are absent from the report`() {
        assertFalse(
            "per-symbol @Suppress(\"ILG062\") must drop the duplicate declaration",
            report.any { it.contains("shared.contract") },
        )
        assertFalse(
            "module-wide ilogger.suppress=ILG035 must drop the suspicious-name warning",
            report.any { it.contains("ILG035") || it.contains("passwordHint") },
        )
    }

    @Test
    fun `compliant fixtures produce no diagnostics`() {
        assertFalse(
            "positive.clean is declared and selects only safe values",
            report.any { it.contains("positive.clean") },
        )
        assertFalse(
            "@LogDao(enabled = false) must not produce an unused-DAO warning",
            report.any { it.contains("ILG053") && it.contains("DisabledDao") },
        )
        assertFalse(
            "opt-in rules stay off unless configured",
            report.any { it.contains("ILG020") || it.contains("ILG021") },
        )
    }
}
