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
fun callCycleEntry(client: ChildClient): String {
    callCyclePeer(client)
    return qualified(client)
}
fun callCyclePeer(client: ChildClient): String = callCycleEntry(client)

fun inlineLeaf(): String = "inline"
fun inlineTarget(): String = inlineLeaf()
inline fun ordinaryInline(block: () -> String): String = block()
fun ordinaryInline(marker: Int, block: () -> String): String = block() + marker
fun ordinaryCallback(block: () -> String): String = block()
inline fun noinlineHelper(noinline block: () -> String): String = block()
inline fun crossinlineHelper(crossinline block: () -> String): String = block()
fun stdlibInline(values: List<Int>): String? = values.firstNotNullOfOrNull { inlineTarget() }
fun explicitInline(): String = ordinaryInline(block = { inlineTarget() })
fun nestedInline(): String = ordinaryInline { ordinaryInline { inlineTarget() } }
fun repeatedInline(): String = ordinaryInline { inlineTarget() + inlineTarget() }
fun returnedInline(): () -> String = { inlineTarget() }
fun storedInline(): String { val stored = { inlineTarget() }; return stored() }
fun callbackInline(): String = ordinaryCallback { inlineTarget() }
fun homonymousInline(): String = ordinaryInline(1) { inlineTarget() }
fun noinlineBoundary(): String = noinlineHelper { inlineTarget() }
fun crossinlineBoundary(): String = crossinlineHelper { inlineTarget() }
fun unsupportedOuter(): String = ordinaryCallback { ordinaryInline { inlineTarget() } }
fun localInline(): String {
    fun local(): String = ordinaryInline { inlineTarget() }
    return local()
}
fun mixedInline(): String { val stored = { inlineTarget() }; return ordinaryInline { inlineTarget() } }
val accessorInline: String get() = ordinaryInline { inlineTarget() }
