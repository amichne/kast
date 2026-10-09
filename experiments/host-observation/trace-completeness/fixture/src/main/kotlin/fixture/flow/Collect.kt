// Upstream kotlinx.coroutines; Apache-2.0. Selected collectIndexed source slice.
// UTF-16 control: 🧪
package fixture.flow

public suspend inline fun <T> Flow<T>.collectIndexed(crossinline action: suspend (index: Int, value: T) -> Unit): Unit =
    collect(object : FlowCollector<T> {
        private var index = 0
        override suspend fun emit(value: T) = action(checkIndexOverflow(index++), value)
    })

// Authored same-shaped control with a distinct anonymous owner.
suspend inline fun <T> Flow<T>.collectSecond(crossinline action: suspend (index: Int, value: T) -> Unit) =
    collect(object : FlowCollector<T> {
        private var index = 0
        override suspend fun emit(value: T) = action(checkIndexOverflow(index++), value)
    })
fun checkIndexOverflow(index: Int): Int {
    check(index >= 0)
    return index
}
