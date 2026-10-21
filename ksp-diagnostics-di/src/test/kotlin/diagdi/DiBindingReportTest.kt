package diagdi

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DiBindingReportTest {

    private val reportFile = File("build/generated/ksp/main/resources/ilogger-diagnostics.txt")

    private val report: List<String> by lazy {
        assertTrue("Expected diagnostics report at $reportFile", reportFile.isFile)
        reportFile.readLines().map { it.trim() }.filter { it.isNotBlank() }
    }

    @Test
    fun `duplicate Hilt binding is reported with its DAO name`() {
        assertReportContains("Duplicate Hilt binding for DAO 'diagdi.DupDao'")
    }

    @Test
    fun `duplicate Koin binding is reported with its DAO name`() {
        assertReportContains("Duplicate Koin binding for DAO 'diagdi.KoinDupDao'")
    }

    @Test
    fun `duplicate diagnostics name the manual fallback`() {
        assertReportContains("db.logged(logger)")
    }

    private fun assertReportContains(expected: String) {
        assertTrue(
            "diagnostics report is missing '$expected': $report",
            report.any { line -> line.contains(expected, ignoreCase = true) },
        )
    }
}
