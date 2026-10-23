package io.ilogger.android.shipping

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ShippingSpoolTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `append, read and acknowledge lines in order`() = runTest {
        val spool = spool()

        spool.append("a")
        spool.appendAll(listOf("b", "c"))

        assertEquals(listOf("a", "b", "c"), spool.pending())
        assertEquals(3, spool.size())

        spool.acknowledge(2)

        assertEquals(listOf("c"), spool.pending())
        spool.acknowledge(1)
        assertEquals(0, spool.size())
        assertFalse(spool.file.exists())
    }

    @Test
    fun `acknowledging more lines than present empties the spool`() = runTest {
        val spool = spool()
        spool.append("a")

        spool.acknowledge(99)

        assertEquals(0, spool.size())
    }

    @Test
    fun `runner sends a batch and acknowledges it on success`() = runTest {
        val spool = spool()
        spool.appendAll(listOf("a", "b", "c"))
        val batches = mutableListOf<List<String>>()
        val runner = ShippingRunner(spool, ShippingTransport { batch -> batches += batch; true })

        assertEquals(ShippingRunner.Outcome.SENT, runner.runOnce())
        assertEquals(listOf(listOf("a", "b", "c")), batches)
        assertEquals(0, spool.size())
    }

    @Test
    fun `runner keeps the spool when the transport refuses`() = runTest {
        val spool = spool()
        spool.appendAll(listOf("a", "b"))
        val runner = ShippingRunner(spool, ShippingTransport { false })

        assertEquals(ShippingRunner.Outcome.FAILED, runner.runOnce())
        assertEquals(listOf("a", "b"), spool.pending())
    }

    @Test
    fun `runner treats a thrown transport error as a failed batch`() = runTest {
        val spool = spool()
        spool.append("a")
        val runner = ShippingRunner(spool, ShippingTransport { error("network down") })

        assertEquals(ShippingRunner.Outcome.FAILED, runner.runOnce())
        assertEquals(listOf("a"), spool.pending())
    }

    @Test
    fun `runner respects the byte budget but always sends one line`() = runTest {
        val spool = spool()
        spool.appendAll(listOf("12345", "67890"))
        val batches = mutableListOf<List<String>>()
        val runner = ShippingRunner(
            spool = spool,
            transport = ShippingTransport { batch -> batches += batch; true },
            maxBatchLines = 10,
            maxBatchBytes = 3,
        )

        assertEquals(ShippingRunner.Outcome.SENT, runner.runOnce())
        assertEquals(ShippingRunner.Outcome.SENT, runner.runOnce())
        assertEquals(ShippingRunner.Outcome.NOTHING, runner.runOnce())
        assertEquals(listOf(listOf("12345"), listOf("67890")), batches)
    }

    private fun kotlinx.coroutines.test.TestScope.spool(): ShippingSpool = ShippingSpool(
        file = File(temporaryFolder.root, "ship/pending.jsonl"),
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )
}
