// Upstream kotlinx.coroutines; Apache-2.0. Flow interface slice; removed library opt-in annotation.
package fixture.flow

public interface Flow<out T> {

    /**
     * Accepts the given [collector] and [emits][FlowCollector.emit] values into it.
     *
     * This method can be used along with SAM-conversion of [FlowCollector]:
     * ```
     * myFlow.collect { value -> println("Collected $value") }
     * ```
     *
     * ### Method inheritance
     *
     * To ensure the context preservation property, it is not recommended implementing this method directly.
     * Instead, [AbstractFlow] can be used as the base type to properly ensure flow's properties.
     *
     * All default flow implementations ensure context preservation and exception transparency properties on a best-effort basis
     * and throw [IllegalStateException] if a violation was detected.
     */
    public suspend fun collect(collector: FlowCollector<T>)
}
