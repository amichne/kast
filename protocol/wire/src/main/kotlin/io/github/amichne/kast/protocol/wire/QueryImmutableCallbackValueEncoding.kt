package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryCaptureContentDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryReturnDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierSelectionDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument

/** Dependency-first factory table gives the wire a finite shape and shares repeated factory context. */
internal fun QueryImmutableCallbackValueDocument.immutableCallbackWire(): QueryImmutableCallbackValueWireDocument {
    val table = CallbackFactoryEncoding()
    val root = table.value(this)
    return QueryImmutableCallbackValueWireDocument(
        root.origin,
        root.source,
        root.destination,
        root.transfers,
        root.invokedCallables,
        table.factories.toList(),
    )
}

private class CallbackFactoryEncoding {
    val factories = mutableListOf<QueryCallbackFactoryWireDocument>()
    private val positions = java.util.IdentityHashMap<QueryCallbackFactoryReturnDocument, Int>()

    fun value(value: QueryImmutableCallbackValueDocument): QueryImmutableCallbackValueNodeWireDocument =
        QueryImmutableCallbackValueNodeWireDocument(
            when (val origin = value.origin) {
                is QueryImmutableCallbackValueOriginDocument.Anonymous ->
                    QueryImmutableCallbackValueOriginWireDocument.Anonymous(origin.body.callbackWire())
                is QueryImmutableCallbackValueOriginDocument.Named ->
                    QueryImmutableCallbackValueOriginWireDocument.Named(
                        origin.basis,
                        origin.occurrence.callbackWire(),
                        origin.target.callbackWire(),
                        origin.dispatchReceiver.callbackWire(),
                        origin.extensionReceiver.callbackWire(),
                    )
                is QueryImmutableCallbackValueOriginDocument.Returned ->
                    QueryImmutableCallbackValueOriginWireDocument.Returned(factory(origin.factory))
            },
            value.source,
            value.destination,
            value.transfers.values,
            value.invokedCallables.values.map { it.callbackWire() },
        )

    private fun factory(factory: QueryCallbackFactoryReturnDocument): Int {
        positions[factory]?.let {
            return it
        }
        val returned = value(factory.returnedValue)
        val captures =
            factory.captures.values.map { capture ->
                QueryCallbackFactoryCaptureWireDocument(
                    capture.binding.callbackWire(),
                    when (val selection = capture.selection) {
                        is QueryCallbackSupplierSelectionDocument.Explicit ->
                            QueryCallbackSupplierSelectionWireDocument.Explicit(selection.argument)
                        is QueryCallbackSupplierSelectionDocument.Default ->
                            QueryCallbackSupplierSelectionWireDocument.Default(selection.declaration.callbackWire())
                    },
                    when (val content = capture.content) {
                        QueryCallbackFactoryCaptureContentDocument.Scalar ->
                            QueryCallbackFactoryCaptureContentWireDocument.Scalar
                        is QueryCallbackFactoryCaptureContentDocument.Callable ->
                            QueryCallbackFactoryCaptureContentWireDocument.Callable(
                                content.values.values.map { value(it) },
                                content.invocations.values.map {
                                    QueryCallbackInvocationWireDocument(
                                        it.occurrence.callbackWire(),
                                        it.owner.callbackWire(),
                                        it.callableTransfers.values,
                                        it.forwardings.values.map { edge -> edge.callbackWire() },
                                    )
                                },
                            )
                    },
                )
            }
        val position = factories.size
        factories +=
            QueryCallbackFactoryWireDocument(
                factory.enclosing,
                factory.invocation,
                factory.callable.callbackWire(),
                returned,
                captures,
                factory.bodyCalls.bodyCallsWire(),
            )
        positions[factory] = position
        return position
    }
}
