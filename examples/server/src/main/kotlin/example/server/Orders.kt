package example.server

import io.ilogger.annotations.Log
import io.ilogger.annotations.LogMeta
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object OrderActions {
    const val CREATED = "order.created"
    const val CANCELLED = "order.cancelled"
}

data class Order(val id: Long, val title: String, val total: Double)

/**
 * Domain interface instrumented by ilogger-ksp. Wrapping calls
 * `orderRepository.logged(logger)` yields events attributed with whatever
 * ambient context is in scope — inside HTTP handlers that is actor +
 * correlation id from the request (see `call.withAuditContext`).
 */
interface OrderRepository {

    @Log(action = OrderActions.CREATED, entity = Order::class, metadata = [LogMeta("title")])
    suspend fun create(title: String): Long

    @Log(action = OrderActions.CANCELLED, entity = Order::class)
    suspend fun cancel(orderId: Long)
}

class InMemoryOrderRepository : OrderRepository {

    private val mutex = Mutex()
    private val orders = HashMap<Long, Order>()
    private var next = 1L

    override suspend fun create(title: String): Long = mutex.withLock {
        val id = next++
        orders[id] = Order(id = id, title = title, total = 4.99 * title.length)
        id
    }

    override suspend fun cancel(orderId: Long): Unit = mutex.withLock {
        orders.remove(orderId)
        Unit
    }
}
