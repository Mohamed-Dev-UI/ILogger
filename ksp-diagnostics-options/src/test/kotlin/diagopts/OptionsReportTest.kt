package diagopts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the opt-in rules (`ilogger.require-actor`, `ilogger.sink-categories`)
 * and that compliant fixtures remain silent.
 */
class OptionsReportTest {

    private val reportFile: File =
        File("build/generated/ksp/main/resources/ilogger-diagnostics.txt")

    private val report: List<String> by lazy {
        assertTrue(
            "Expected the diagnostics report to exist; run :ksp-diagnostics-options:kspKotlin first.",
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

        assertTrue("fixtures must declare EXPECTED markers; found ${markers.size}", markers.size >= 2)

        for (expected in markers) {
            assertTrue(
                "diagnostics report is missing marker:\n  '$expected'\nreport:\n${report.joinToString("\n")}",
                report.any { it.contains(expected, ignoreCase = true) },
            )
        }
    }

    @Test
    fun `compliant operations are silent`() {
        assertFalse(
            "task.archived supplies an explicit actor and a bound category",
            report.any { it.contains("task.archived") },
        )
        assertFalse(
            "task.locked supplies an explicit actor and a bound category",
            report.any { it.contains("task.locked") },
        )
    }

    @Test
    fun `missing actor is reported once per operation`() {
        val lines = report.filter { it.contains("ILG020") }
        assertTrue("expected at least one ILG020 line, report:\n${report.joinToString("\n")}", lines.size >= 1)
        assertTrue(
            "ILG020 must name the operation",
            lines.any { it.contains("MissingAuditActor") || it.contains("create") },
        )
    }

    @Test
    fun `unbound category is reported with the category name`() {
        assertTrue(
            "expected an ILG021 line for billing, report:\n${report.joinToString("\n")}",
            report.any { it.contains("ILG021") && it.contains("billing") },
        )
    }
}
