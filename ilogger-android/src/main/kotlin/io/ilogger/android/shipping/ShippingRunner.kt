package io.ilogger.android.shipping

/**
 * Sends one bounded batch from the spool.
 *
 * A batch is either sent in full and acknowledged, or left untouched. This
 * keeps the receiver's idempotency requirement simple: the same line may be
 * delivered again after a crash between `send` and `acknowledge`.
 */
internal class ShippingRunner(
    private val spool: ShippingSpool,
    private val transport: ShippingTransport,
    private val maxBatchLines: Int = DEFAULT_MAX_BATCH_LINES,
    private val maxBatchBytes: Int = DEFAULT_MAX_BATCH_BYTES,
) {

    internal enum class Outcome { NOTHING, SENT, FAILED }

    suspend fun runOnce(): Outcome {
        val pending = spool.pending(maxBatchLines)
        if (pending.isEmpty()) return Outcome.NOTHING
        val batch = takeWithinBudget(pending)
        val accepted = runCatching { transport.send(batch) }.getOrElse { false }
        if (!accepted) return Outcome.FAILED
        spool.acknowledge(batch.size)
        return Outcome.SENT
    }

    private fun takeWithinBudget(lines: List<String>): List<String> {
        var bytes = 0
        val batch = ArrayList<String>(minOf(lines.size, maxBatchLines))
        for (line in lines) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            // Always send at least one line, even when it exceeds the budget:
            // otherwise an oversized line would block the spool forever.
            if (batch.isNotEmpty() && bytes + lineBytes > maxBatchBytes) break
            batch += line
            bytes += lineBytes
        }
        return batch
    }

    internal companion object {
        const val DEFAULT_MAX_BATCH_LINES: Int = 500
        const val DEFAULT_MAX_BATCH_BYTES: Int = 256 * 1024
    }
}
