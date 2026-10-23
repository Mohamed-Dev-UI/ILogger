package io.ilogger.ksp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the public catalog in `docs/23-diagnostics-catalog.md` in lockstep
 * with the processor enum. The doc is the contract users see; a code added
 * without a row (or a row whose severity/summary drifted) fails the build.
 */
class DiagnosticCatalogTest {

    private data class Row(
        val code: String,
        val severity: String,
        val summary: String,
        val fix: String,
    )

    private val catalogFile: File = File("../docs/23-diagnostics-catalog.md")

    private val catalog: Map<String, Row> by lazy {
        assertTrue(
            "Expected the diagnostics catalog at ${catalogFile.absolutePath}",
            catalogFile.isFile,
        )
        val rowPattern = Regex("""^\|\s*`(ILG\d{3})`\s*\|\s*(error|warning)\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|$""")
        val rows = LinkedHashMap<String, Row>()
        catalogFile.readLines().forEach { line ->
            val match = rowPattern.matchEntire(line.trim()) ?: return@forEach
            val row = Row(
                code = match.groupValues[1],
                severity = match.groupValues[2],
                summary = match.groupValues[3],
                fix = match.groupValues[4],
            )
            assertTrue("Duplicate catalog row for ${row.code}", rows.put(row.code, row) == null)
        }
        rows
    }

    @Test
    fun `codes are unique and follow the stable ILGnnn format`() {
        val codes = Diagnostic.entries.map { it.code }
        assertEquals("diagnostic codes must be unique", codes.size, codes.toSet().size)
        for (code in codes) {
            assertTrue("'$code' must match ILG followed by three digits", Regex("ILG\\d{3}").matches(code))
        }
    }

    @Test
    fun `every processor diagnostic is documented with matching severity and summary`() {
        for (diagnostic in Diagnostic.entries) {
            val row = catalog[diagnostic.code]
            assertNotNull("docs/23 is missing a row for ${diagnostic.code}", row)
            row!!
            assertEquals(
                "severity mismatch for ${diagnostic.code}",
                diagnostic.severity.name.lowercase(),
                row.severity,
            )
            assertEquals(
                "summary mismatch for ${diagnostic.code}",
                diagnostic.summary,
                row.summary,
            )
        }
    }

    @Test
    fun `every catalog row maps to a live processor diagnostic`() {
        val live = Diagnostic.entries.map { it.code }.toSet()
        for (code in catalog.keys) {
            assertTrue("docs/23 documents retired code $code", code in live)
        }
    }

    @Test
    fun `every diagnostic carries an actionable fix`() {
        for (diagnostic in Diagnostic.entries) {
            assertTrue(
                "${diagnostic.code} needs a non-blank generic fix",
                diagnostic.fix.isNotBlank(),
            )
            assertTrue(
                "docs/23 needs a non-blank fix for ${diagnostic.code}",
                catalog.getValue(diagnostic.code).fix.isNotBlank(),
            )
        }
    }
}
