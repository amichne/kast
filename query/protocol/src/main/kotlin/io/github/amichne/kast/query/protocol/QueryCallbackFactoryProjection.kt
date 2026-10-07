package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryCaptureContentDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryCaptureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryReturnDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierSelectionDocument
import io.github.amichne.kast.relation.contract.CallbackFactoryCapture
import io.github.amichne.kast.relation.contract.CallbackFactoryCaptureContent
import io.github.amichne.kast.relation.contract.CallbackFactoryCaptureSelection
import io.github.amichne.kast.relation.contract.CallbackFactoryReturn

internal fun CallbackFactoryReturn.projectFactoryReturn(
    projection: CallbackProjection
): QueryCallbackFactoryReturnDocument? {
    return QueryCallbackFactoryReturnDocument.create(
            invocation.enclosing.impactDeclaration().factoryValue() ?: return null,
            invocation.impactDocument().factoryValue() ?: return null,
            projection.callable(invocation.callable) ?: return null,
            returnedValue.projectImmutableValue(projection) ?: return null,
            BoundedProtocolList.create(captures.map { it.projectFactoryCapture(projection) ?: return null })
                .factoryValue() ?: return null,
            bodyCalls.projectFactoryBodyCalls(projection) ?: return null,
        )
        .factoryValue()
}

private fun CallbackFactoryCapture.projectFactoryCapture(
    projection: CallbackProjection
): QueryCallbackFactoryCaptureDocument? {
    val selection =
        when (val selected = selection) {
            is CallbackFactoryCaptureSelection.Explicit ->
                QueryCallbackSupplierSelectionDocument.Explicit(
                    selected.value.impactDocument().factoryValue() ?: return null
                )
            is CallbackFactoryCaptureSelection.Default ->
                QueryCallbackSupplierSelectionDocument.Default(
                    QueryCallbackBindingDocument.Default(
                        projection.parameter(selected.declaration.parameter) ?: return null,
                        projection.occurrence(selected.declaration.defaultValue) ?: return null,
                    )
                )
        }
    val content =
        when (val captured = content) {
            CallbackFactoryCaptureContent.Scalar -> QueryCallbackFactoryCaptureContentDocument.Scalar
            is CallbackFactoryCaptureContent.Callable ->
                QueryCallbackFactoryCaptureContentDocument.Callable(
                    BoundedProtocolList.create(
                            captured.values.map { it.projectImmutableValue(projection) ?: return null }
                        )
                        .factoryValue() ?: return null,
                    BoundedProtocolList.create(captured.invocations.map { projection.invocation(it) ?: return null })
                        .factoryValue() ?: return null,
                )
        }
    return QueryCallbackFactoryCaptureDocument.create(projection.bound(binding) ?: return null, selection, content)
        .factoryValue()
}

private fun <V, F> Refinement<V, F>.factoryValue(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
