package benchmark

import io.ilogger.annotations.Log
import io.ilogger.annotations.LogMeta

/** Domain object selected by the generated wrapper's metadata expressions. */
data class BenchItem(val id: Long, val label: String)

/**
 * Real `@Log` interface: the benchmark exercises the KotlinPoet-generated
 * wrapper (`BenchRepository.logged(logger)`), not a hand-written imitation.
 */
interface BenchRepository {

    @Log(action = "bench.touch")
    suspend fun touch(id: Long)

    @Log(
        action = "bench.save",
        metadata = [LogMeta("item.id", name = "itemId"), LogMeta("item.label")],
    )
    suspend fun save(item: BenchItem): Long
}

class InMemoryBenchRepository : BenchRepository {
    override suspend fun touch(id: Long) = Unit

    override suspend fun save(item: BenchItem): Long = item.id
}
