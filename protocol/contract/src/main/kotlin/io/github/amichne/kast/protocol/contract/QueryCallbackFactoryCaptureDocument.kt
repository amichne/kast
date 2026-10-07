package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryCallbackFactoryCaptureContentDocument {
    data object Scalar : QueryCallbackFactoryCaptureContentDocument

    data class Callable(
        val values: BoundedProtocolList<QueryImmutableCallbackValueDocument>,
        val invocations: BoundedProtocolList<QueryCallbackInvocationDocument>,
    ) : QueryCallbackFactoryCaptureContentDocument
}

@ConsistentCopyVisibility
data class QueryCallbackFactoryCaptureDocument
private constructor(
    val binding: QueryCallbackBindingDocument.Bound,
    val selection: QueryCallbackSupplierSelectionDocument,
    val content: QueryCallbackFactoryCaptureContentDocument,
) {
    companion object {
        fun create(
            binding: QueryCallbackBindingDocument.Bound,
            selection: QueryCallbackSupplierSelectionDocument,
            content: QueryCallbackFactoryCaptureContentDocument,
        ): Refinement<QueryCallbackFactoryCaptureDocument, QueryCallbackFactoryFailure> {
            if (!binding.admitsSupplierBinding(binding.invocation.callable.basis)) return invalidCapture()
            if (!selection.matchesCapture(binding)) return invalidCapture()
            if (!content.admitsSelectedValues(binding, selection)) return invalidCapture()
            return Refinement.Refined(QueryCallbackFactoryCaptureDocument(binding, selection, content))
        }
    }
}

private fun QueryCallbackSupplierSelectionDocument.matchesCapture(
    binding: QueryCallbackBindingDocument.Bound
): Boolean =
    when (this) {
        is QueryCallbackSupplierSelectionDocument.Explicit -> matchesExplicitCapture(binding)
        is QueryCallbackSupplierSelectionDocument.Default ->
            declaration.parameter.sameParameter(binding.parameterIdentity()) &&
                declaration.parameter.parameter.contains(declaration.defaultValue)
    }

private fun QueryCallbackSupplierSelectionDocument.Explicit.matchesExplicitCapture(
    binding: QueryCallbackBindingDocument.Bound
): Boolean {
    val role = argument.role as? ImpactValueRoleDocument.Argument ?: return false
    if (role.invocation != binding.invocation || role.index != binding.position) return false
    if (
        argument.enclosing.basis != binding.invocation.callable.basis ||
            argument.range.start.value >= argument.range.end.value
    )
        return false
    return argument.range.start.value >= binding.invocation.range.start.value &&
        argument.range.end.value <= binding.invocation.range.end.value
}

private fun QueryCallbackFactoryCaptureContentDocument.admitsSelectedValues(
    binding: QueryCallbackBindingDocument.Bound,
    selection: QueryCallbackSupplierSelectionDocument,
): Boolean =
    when (this) {
        QueryCallbackFactoryCaptureContentDocument.Scalar -> true
        is QueryCallbackFactoryCaptureContentDocument.Callable -> {
            if (values.values.isEmpty() || values.values.distinct().size != values.values.size) false
            else
                values.values.all {
                    QueryCallbackParameterSupplierDocument.create(binding, selection, it) is Refinement.Refined
                }
        }
    }

private fun invalidCapture() = Refinement.Rejected(QueryCallbackFactoryFailure.INVALID_CAPTURE)
