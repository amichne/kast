package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryCallbackSupplierSelectionDocument {
    data class Explicit(val argument: ImpactValueSiteReferenceDocument) : QueryCallbackSupplierSelectionDocument

    /** The enclosing supplier binding identifies the exact call whose argument was omitted. */
    data class Default(val declaration: QueryCallbackBindingDocument.Default) : QueryCallbackSupplierSelectionDocument
}

enum class QueryCallbackSupplierFailure {
    INVALID_BINDING,
    INVALID_SELECTION,
    INVALID_VALUE,
    INVALID_INVENTORY,
}

@ConsistentCopyVisibility
data class QueryCallbackParameterSupplierDocument
private constructor(
    val binding: QueryCallbackBindingDocument.Bound,
    val selection: QueryCallbackSupplierSelectionDocument,
    val value: QueryImmutableCallbackValueDocument,
) {
    companion object {
        fun create(
            binding: QueryCallbackBindingDocument.Bound,
            selection: QueryCallbackSupplierSelectionDocument,
            value: QueryImmutableCallbackValueDocument,
        ): Refinement<QueryCallbackParameterSupplierDocument, QueryCallbackSupplierFailure> {
            if (!binding.admitsSupplierBinding(value.source.enclosing.basis))
                return Refinement.Rejected(QueryCallbackSupplierFailure.INVALID_BINDING)
            when (val selected = selection.admitSupplier(binding, value)) {
                is Refinement.Rejected -> return selected
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(QueryCallbackParameterSupplierDocument(binding, selection, value))
        }
    }
}

internal fun QueryCallbackBindingDocument.Bound.admitsSupplierBinding(basis: ImpactSemanticBasisDocument): Boolean {
    val owner =
        when (val admitted = invocationOwner.admitOwner()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return false
        }
    if (!validCallableMapping(basis) || !validInvocationSite()) return false
    return parameterIdentity().validParameter() && owner.contains(invocationOccurrence)
}

private fun QueryCallbackSupplierSelectionDocument.admitSupplier(
    binding: QueryCallbackBindingDocument.Bound,
    value: QueryImmutableCallbackValueDocument,
): Refinement<Unit, QueryCallbackSupplierFailure> {
    when (this) {
        is QueryCallbackSupplierSelectionDocument.Explicit -> {
            val role =
                argument.role as? ImpactValueRoleDocument.Argument
                    ?: return Refinement.Rejected(QueryCallbackSupplierFailure.INVALID_SELECTION)
            if (role.invocation != binding.invocation || role.index != binding.position)
                return Refinement.Rejected(QueryCallbackSupplierFailure.INVALID_SELECTION)
            if (value.destination != argument) return Refinement.Rejected(QueryCallbackSupplierFailure.INVALID_VALUE)
        }
        is QueryCallbackSupplierSelectionDocument.Default -> {
            if (
                !declaration.parameter.sameParameter(binding.parameterIdentity()) ||
                    !declaration.parameter.parameter.contains(declaration.defaultValue)
            )
                return Refinement.Rejected(QueryCallbackSupplierFailure.INVALID_SELECTION)
            if (!value.destination.matchesSelectedDefault(binding, declaration))
                return Refinement.Rejected(QueryCallbackSupplierFailure.INVALID_VALUE)
        }
    }
    return Refinement.Refined(Unit)
}

private fun ImpactValueSiteReferenceDocument.matchesSelectedDefault(
    binding: QueryCallbackBindingDocument.Bound,
    declaration: QueryCallbackBindingDocument.Default,
): Boolean {
    if (enclosing != binding.callable.reference(enclosing.basis) || enclosing.file != declaration.defaultValue.file)
        return false
    return range.start == declaration.defaultValue.range.startInclusive &&
        range.end == declaration.defaultValue.range.endExclusive
}
