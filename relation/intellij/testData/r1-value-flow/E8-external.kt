package outside
fun <T> supply(body: () -> T): T = body()
fun unrelated(): List<String> = listOf("independent").map { it.uppercase() }
