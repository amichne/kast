package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterSupplierDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierSelectionDocument
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CallbackSupplierSelection

internal fun CallbackParameterSupplier.projectCallbackSupplier(
    projection: CallbackProjection
): QueryCallbackParameterSupplierDocument? {
    val selected =
        when (val selected = selection) {
            is CallbackSupplierSelection.Explicit ->
                when (val argument = selected.argument.impactDocument()) {
                    is Refinement.Refined -> QueryCallbackSupplierSelectionDocument.Explicit(argument.value)
                    is Refinement.Rejected -> return null
                }
            is CallbackSupplierSelection.Default ->
                QueryCallbackSupplierSelectionDocument.Default(
                    QueryCallbackBindingDocument.Default(
                        projection.parameter(selected.declaration.parameter) ?: return null,
                        projection.occurrence(selected.declaration.defaultValue) ?: return null,
                    )
                )
        }
    return when (
        val admitted =
            QueryCallbackParameterSupplierDocument.create(
                projection.bound(binding) ?: return null,
                selected,
                value.projectImmutableValue(projection) ?: return null,
            )
    ) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> null
    }
}
