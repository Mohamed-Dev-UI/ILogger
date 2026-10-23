package example.plugin

import io.ilogger.annotations.Log
import io.ilogger.annotations.LogActions
import io.ilogger.annotations.LogConfig

object OrderActions {
    const val PLACED = "order.placed"
}

@LogActions(OrderActions.PLACED)
object OrderActionRegistry

@LogConfig(category = "orders")
interface OrderRepository {
    @Log(action = OrderActions.PLACED, entityId = "'order'")
    suspend fun place(id: String, total: Int): Long
}
