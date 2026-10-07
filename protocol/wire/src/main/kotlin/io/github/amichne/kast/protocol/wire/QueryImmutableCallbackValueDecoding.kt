package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryCaptureContentDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryCaptureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryReturnDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument

private const val MAX_FACTORY_TABLE_ENTRIES = 1000

internal fun QueryImmutableCallbackValueWireDocument.toContract():
    WireDocumentConversion<QueryImmutableCallbackValueDocument> {
    if (factories.size > MAX_FACTORY_TABLE_ENTRIES) return WireDocumentConversion.Rejected
    val admitted = mutableListOf<QueryCallbackFactoryReturnDocument>()
    for (factory in factories) {
        when (val result = factory.toContract(admitted)) {
            is WireDocumentConversion.Converted -> admitted += result.value
            WireDocumentConversion.Rejected -> return WireDocumentConversion.Rejected
        }
    }
    if (!hasExactFactoryClosure()) return WireDocumentConversion.Rejected
    return node().toContract(admitted)
}

private fun QueryImmutableCallbackValueWireDocument.hasExactFactoryClosure(): Boolean {
    val reachable = linkedSetOf<Int>()
    val pending = ArrayDeque<Int>()
    (origin as? QueryImmutableCallbackValueOriginWireDocument.Returned)?.let { pending.add(it.factoryIndex) }
    while (pending.isNotEmpty()) {
        val position = pending.removeFirst()
        if (position !in factories.indices) return false
        if (!reachable.add(position)) continue
        val factory = factories[position]
        val values =
            listOf(factory.returnedValue) +
                factory.captures.flatMap { capture ->
                    when (val content = capture.content) {
                        QueryCallbackFactoryCaptureContentWireDocument.Scalar -> emptyList()
                        is QueryCallbackFactoryCaptureContentWireDocument.Callable -> content.values
                    }
                }
        values.forEach { value ->
            (value.origin as? QueryImmutableCallbackValueOriginWireDocument.Returned)?.let {
                pending.add(it.factoryIndex)
            }
        }
    }
    if (reachable.size != factories.size) return false
    return true
}

private fun QueryCallbackFactoryWireDocument.toContract(
    prior: List<QueryCallbackFactoryReturnDocument>
): WireDocumentConversion<QueryCallbackFactoryReturnDocument> =
    combineConverted(
            callable.toContract(),
            returnedValue.toContract(prior),
            captures
                .convertEach { it.toContract(prior) }
                .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
            bodyCalls.toContract(),
        ) { callable, returned, captures, bodyCalls ->
            QueryCallbackFactoryReturnDocument.create(enclosing, invocation, callable, returned, captures, bodyCalls)
                .toWireDocumentConversion()
        }
        .flattenConverted()

private fun QueryCallbackFactoryCaptureWireDocument.toContract(
    prior: List<QueryCallbackFactoryReturnDocument>
): WireDocumentConversion<QueryCallbackFactoryCaptureDocument> =
    combineConverted(binding.toContract(), selection.toContract(), content.toContract(prior)) {
            binding,
            selection,
            content ->
            if (binding is QueryCallbackBindingDocument.Bound)
                QueryCallbackFactoryCaptureDocument.create(binding, selection, content).toWireDocumentConversion()
            else WireDocumentConversion.Rejected
        }
        .flattenConverted()

private fun QueryCallbackFactoryCaptureContentWireDocument.toContract(
    prior: List<QueryCallbackFactoryReturnDocument>
): WireDocumentConversion<QueryCallbackFactoryCaptureContentDocument> =
    when (this) {
        QueryCallbackFactoryCaptureContentWireDocument.Scalar ->
            WireDocumentConversion.Converted(QueryCallbackFactoryCaptureContentDocument.Scalar)
        is QueryCallbackFactoryCaptureContentWireDocument.Callable ->
            combineConverted(
                values
                    .convertEach { it.toContract(prior) }
                    .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
                invocations
                    .convertEach { it.toContract() }
                    .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
            ) { values, invocations ->
                QueryCallbackFactoryCaptureContentDocument.Callable(values, invocations)
            }
    }

private fun QueryImmutableCallbackValueNodeWireDocument.toContract(
    prior: List<QueryCallbackFactoryReturnDocument>
): WireDocumentConversion<QueryImmutableCallbackValueDocument> =
    combineConverted(
            origin.toContract(prior),
            BoundedProtocolList.create(transfers).toWireDocumentConversion(),
            invokedCallables
                .convertEach { it.toContract() }
                .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() },
        ) { origin, transfers, callables ->
            QueryImmutableCallbackValueDocument.create(origin, source, destination, transfers, callables)
                .toWireDocumentConversion()
        }
        .flattenConverted()

private fun QueryImmutableCallbackValueOriginWireDocument.toContract(
    prior: List<QueryCallbackFactoryReturnDocument>
): WireDocumentConversion<QueryImmutableCallbackValueOriginDocument> =
    when (this) {
        is QueryImmutableCallbackValueOriginWireDocument.Returned ->
            if (factoryIndex in prior.indices)
                WireDocumentConversion.Converted(
                    QueryImmutableCallbackValueOriginDocument.Returned(prior[factoryIndex])
                )
            else WireDocumentConversion.Rejected
        is QueryImmutableCallbackValueOriginWireDocument.Anonymous ->
            body.toContract().flatMapConverted {
                if (it is QueryCallbackBodyDocument.Anonymous)
                    WireDocumentConversion.Converted(QueryImmutableCallbackValueOriginDocument.Anonymous(it))
                else WireDocumentConversion.Rejected
            }
        is QueryImmutableCallbackValueOriginWireDocument.Named ->
            combineConverted(
                occurrence.toContract(),
                target.toContract(),
                dispatchReceiver.toContract(),
                extensionReceiver.toContract(),
            ) { occurrence, target, dispatch, extension ->
                QueryImmutableCallbackValueOriginDocument.Named(basis, occurrence, target, dispatch, extension)
            }
    }
