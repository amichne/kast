package fixture.orders

// Fixture identities are synthetic and apply only to decision replay.
fun submit(id: String): String = "order:$id"
fun submit(id: Long): Long = id + 1
fun submit(id: String, retries: Int): String = id.repeat(retries)
class OrderService
fun renderReceipt(id: String): String {
    return "Order, \"$id\":\nready\t✓"
}
