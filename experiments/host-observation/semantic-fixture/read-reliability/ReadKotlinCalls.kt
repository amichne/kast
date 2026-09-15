package fixture.calls

interface BaseClient { fun fetch(): String }
interface ChildClient : BaseClient
class ConcreteClient : ChildClient { override fun fetch(): String = "value" }
fun String.adapt(): Int = length
fun Int.adapt(): String = toString()
fun outer(client: ChildClient): Int {
    val value = client.fetch()
    return value.adapt()
}
fun repeated(client: ChildClient): String = client.fetch() + client.fetch()
fun unused(): String = "unused"
fun callback(client: ChildClient): () -> String = { client.fetch() }
fun localFunction(client: ChildClient): String {
    fun nested(): String = client.fetch()
    return nested()
}
class DelegatingClient(delegate: BaseClient) : BaseClient by delegate
fun delegated(client: DelegatingClient): String = client.fetch()
fun interface Fetcher { operator fun invoke(): String }
fun explicitInvoke(fetcher: Fetcher): String = fetcher.invoke()
fun implicitInvoke(fetcher: Fetcher): String = fetcher()
fun sam(client: ChildClient): Fetcher = Fetcher { client.fetch() }
fun integerExtension(value: Int): String = value.adapt()
fun qualified(client: ChildClient): String {
    val callback = { client.fetch() }
    return client.fetch()
}
