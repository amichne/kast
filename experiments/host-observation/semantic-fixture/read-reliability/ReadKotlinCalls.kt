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
fun immediateLiteralCallback(): String = ({ inlineTarget() })()
fun explicitLiteralCallback(): String = ({ inlineTarget() }).invoke()
fun explicitAnonymousCallback(): String = (fun(): String { return inlineTarget() }).invoke()
fun ignoredCallbackTarget(): Unit = Unit
operator fun (() -> Unit).invoke(ignored: Int) {}
fun ignoredExplicitCallback(): Unit = ({ ignoredCallbackTarget() }).invoke(1)
fun ignoredImplicitCallback(): Unit = ({ ignoredCallbackTarget() })(1)
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

fun storedParameter(block: () -> String): String {
    val saved = block
    return saved()
}
fun storedParameterCallback(): String = storedParameter { inlineTarget() }
fun explicitParameter(block: () -> String): String = block.invoke()
fun explicitParameterCallback(): String = explicitParameter { inlineTarget() }
fun mutableParameter(block: () -> String): String {
    var saved = block
    saved = { "replacement" }
    return saved()
}
fun mutableParameterCallback(): String = mutableParameter { inlineTarget() }
var deferredOperation: () -> String = { "initial" }
fun storeOperation(block: () -> String): String {
    deferredOperation = block
    return "stored"
}
fun storedOperationCallback(): String = storeOperation { inlineTarget() }
fun selectedOperation(unused: () -> String, selected: () -> String): String = selected()
fun selectedParameterCallback(): String = selectedOperation(unused = { "unused" }, selected = { inlineTarget() })
fun uninvokedParameterCallback(): String = selectedOperation(unused = { inlineTarget() }, selected = { "selected" })

inline fun defaultInlineDefinition(block: () -> String = { inlineTarget() }): String = block()
fun omittedDefaultInline(): String = defaultInlineDefinition()
fun suppliedDefaultInline(): String = defaultInlineDefinition { inlineTarget() }
fun replacedDefaultInline(): String = defaultInlineDefinition { "replacement" }
fun defaultOrdinaryDefinition(block: () -> String = { inlineTarget() }): String = block()
fun omittedDefaultOrdinary(): String = defaultOrdinaryDefinition()
fun suppliedDefaultOrdinary(): String = defaultOrdinaryDefinition { inlineTarget() }
fun replacedDefaultOrdinary(): String = defaultOrdinaryDefinition { "replacement" }
inline fun defaultMethodInlineDefinition(client: ChildClient, block: () -> String = { client.fetch() }): String = block()
fun omittedDefaultMethodInline(client: ChildClient): String = defaultMethodInlineDefinition(client)
fun replacedDefaultMethodInline(client: ChildClient): String = defaultMethodInlineDefinition(client) { "replacement" }
fun defaultMethodOrdinaryDefinition(client: ChildClient, block: () -> String = { client.fetch() }): String = block()
fun omittedDefaultMethodOrdinary(client: ChildClient): String = defaultMethodOrdinaryDefinition(client)
fun replacedDefaultMethodOrdinary(client: ChildClient): String = defaultMethodOrdinaryDefinition(client) { "replacement" }

fun labelledInline(): String = ordinaryInline label@ { inlineTarget() }
fun labelledOrdinary(): String = ordinaryCallback label@ { inlineTarget() }
fun nestedDirectInline(): String = ordinaryInline { ({ inlineTarget() })() }
fun nestedDirectOrdinary(): String = ordinaryCallback { ({ inlineTarget() })() }
fun anonymousFunInline(): String = ordinaryInline(fun(): String { return inlineTarget() })
fun anonymousFunOrdinary(): String = ordinaryCallback(fun(): String { return inlineTarget() })

inline fun forwardingInlineHelper(block: () -> String): String = ordinaryInline(block)
inline fun forwardingInlineTwiceHelper(block: () -> String): String = forwardingInlineHelper(block)
fun forwardedInline(): String = forwardingInlineHelper { inlineTarget() }
fun forwardedInlineTwice(): String = forwardingInlineTwiceHelper { inlineTarget() }

fun nestedNamedForwardingHelper(block: () -> String): String {
    fun deferred(): String = ordinaryCallback(block)
    return "deferred"
}
fun nestedNamedForwardingCallback(): String = nestedNamedForwardingHelper { inlineTarget() }

inline fun nestedCrossinlineHelper(crossinline block: () -> String): String = ordinaryCallback { block() }
fun nestedCrossinlineCallback(): String = nestedCrossinlineHelper { inlineTarget() }
