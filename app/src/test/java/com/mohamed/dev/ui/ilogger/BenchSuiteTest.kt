package com.mohamed.dev.ui.ilogger

import com.mohamed.dev.ui.ilogger.ui.BenchSuite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app TestKit screen and this test run the same suite, so a failed
 * library contract fails CI as well as the demo UI.
 */
class BenchSuiteTest {

    @Test
    fun allDeterministicChecksPass() = runBlocking {
        val checks = BenchSuite.run()

        assertEquals(10, checks.size)
        val failed = checks.filterNot { it.passed }
        assertTrue(
            "failed checks: " + failed.joinToString { "${it.name} (expected=${it.expected}, actual=${it.actual})" },
            failed.isEmpty(),
        )
    }
}
