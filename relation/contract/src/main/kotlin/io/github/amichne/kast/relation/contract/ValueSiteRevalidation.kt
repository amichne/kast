package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Supplied structural claims select native work; they are never compiler evidence. */
sealed interface ValueSiteRoleClaim {
    data object ExpressionResult : ValueSiteRoleClaim

    data object LocalBinding : ValueSiteRoleClaim

    data object LocalRead : ValueSiteRoleClaim

    data object Return : ValueSiteRoleClaim

    data object PropertyAssignment : ValueSiteRoleClaim

    data class Argument(
        val invocationAnchor: ExactDeclarationTextRange,
        val expectedCallable: SymbolSelector,
        val position: ValueArgumentPosition,
    ) : ValueSiteRoleClaim
}

enum class ValueSiteRevalidationRequestFailure {
    BASIS_MISMATCH,
    ANCHOR_OUTSIDE_ENCLOSING,
    INVOCATION_OUTSIDE_ENCLOSING,
    ARGUMENT_OUTSIDE_INVOCATION,
    ARGUMENT_POSITION_UNAVAILABLE,
}

/** Exact positions are revalidated on their selected owner scope, independently of expansion D. */
class ValueSiteRevalidationRequest
private constructor(
    val enclosing: SymbolSelector,
    val anchor: ExactDeclarationTextRange,
    val role: ValueSiteRoleClaim,
    val budget: RelationBudget,
) {
    companion object {
        fun create(
            enclosing: SymbolSelector,
            anchor: ExactDeclarationTextRange,
            role: ValueSiteRoleClaim,
            budget: RelationBudget,
        ): Refinement<ValueSiteRevalidationRequest, ValueSiteRevalidationRequestFailure> {
            if (!enclosing.range.containsValueRange(anchor))
                return Refinement.Rejected(ValueSiteRevalidationRequestFailure.ANCHOR_OUTSIDE_ENCLOSING)
            if (role is ValueSiteRoleClaim.Argument) {
                if (role.expectedCallable.lease.identity != enclosing.lease.identity)
                    return Refinement.Rejected(ValueSiteRevalidationRequestFailure.BASIS_MISMATCH)
                if (!enclosing.range.containsValueRange(role.invocationAnchor))
                    return Refinement.Rejected(ValueSiteRevalidationRequestFailure.INVOCATION_OUTSIDE_ENCLOSING)
                if (!role.invocationAnchor.containsValueRange(anchor))
                    return Refinement.Rejected(ValueSiteRevalidationRequestFailure.ARGUMENT_OUTSIDE_INVOCATION)
                val signature = role.expectedCallable.signature
                if (
                    signature !is CanonicalCompilerSignature.Function ||
                        role.position.value !in signature.valueParameters.indices
                )
                    return Refinement.Rejected(ValueSiteRevalidationRequestFailure.ARGUMENT_POSITION_UNAVAILABLE)
            }
            return Refinement.Refined(ValueSiteRevalidationRequest(enclosing, anchor, role, budget))
        }
    }
}

enum class ValueSiteRevalidationFailure {
    BASIS_MISMATCH,
    ENCLOSING_MISMATCH,
    ANCHOR_MISMATCH,
    ROLE_MISMATCH,
    INVOCATION_MISMATCH,
    CALLABLE_MISMATCH,
    ARGUMENT_POSITION_MISMATCH,
}

/** Actual native role evidence, bound to the full exact selected declaration and occurrence. */
class RevalidatedValueSite private constructor(val site: ValueSite) {
    companion object {
        fun fromCompiler(
            request: ValueSiteRevalidationRequest,
            site: ValueSite,
        ): Refinement<RevalidatedValueSite, ValueSiteRevalidationFailure> {
            if (site.basis != request.enclosing.lease.identity)
                return Refinement.Rejected(ValueSiteRevalidationFailure.BASIS_MISMATCH)
            if (!site.enclosing.matchesSelected(request.enclosing))
                return Refinement.Rejected(ValueSiteRevalidationFailure.ENCLOSING_MISMATCH)
            if (site.range != request.anchor) return Refinement.Rejected(ValueSiteRevalidationFailure.ANCHOR_MISMATCH)
            when (val admitted = request.role.admitRole(site.role, request.enclosing)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(RevalidatedValueSite(site))
        }
    }
}

private fun ValueSiteRoleClaim.admitRole(
    actual: ValueRole,
    owner: SymbolSelector,
): Refinement<Unit, ValueSiteRevalidationFailure> =
    when (this) {
        ValueSiteRoleClaim.ExpressionResult -> admitSimpleRole(actual, ValueRole.ExpressionResult)
        ValueSiteRoleClaim.LocalBinding -> admitSimpleRole(actual, ValueRole.LocalBinding)
        ValueSiteRoleClaim.LocalRead -> admitSimpleRole(actual, ValueRole.LocalRead)
        ValueSiteRoleClaim.Return -> admitSimpleRole(actual, ValueRole.Return)
        ValueSiteRoleClaim.PropertyAssignment -> admitSimpleRole(actual, ValueRole.PropertyAssignment)
        is ValueSiteRoleClaim.Argument -> admitArgument(actual, owner)
    }

private fun admitSimpleRole(actual: ValueRole, expected: ValueRole): Refinement<Unit, ValueSiteRevalidationFailure> =
    if (actual == expected) Refinement.Refined(Unit)
    else Refinement.Rejected(ValueSiteRevalidationFailure.ROLE_MISMATCH)

private fun ValueSiteRoleClaim.Argument.admitArgument(
    actual: ValueRole,
    owner: SymbolSelector,
): Refinement<Unit, ValueSiteRevalidationFailure> {
    if (actual !is ValueRole.Argument) return Refinement.Rejected(ValueSiteRevalidationFailure.ROLE_MISMATCH)
    if (
        actual.call.basis != owner.lease.identity ||
            !actual.call.enclosing.matchesSelected(owner) ||
            actual.call.range != invocationAnchor
    )
        return Refinement.Rejected(ValueSiteRevalidationFailure.INVOCATION_MISMATCH)
    if (!actual.call.callable.matchesSelected(expectedCallable))
        return Refinement.Rejected(ValueSiteRevalidationFailure.CALLABLE_MISMATCH)
    if (actual.position != position) return Refinement.Rejected(ValueSiteRevalidationFailure.ARGUMENT_POSITION_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun RelationEndpoint.matchesSelected(selector: SymbolSelector): Boolean =
    compilerIdentity == selector.compilerIdentity && file == selector.file && range == selector.range

enum class ValueSiteRevalidationLimit {
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
}

sealed interface ValueModelSiteRead {
    data class Revalidated(val position: RevalidatedValueSite, val examinedWorkUnits: RelationWorkCount) :
        ValueModelSiteRead

    data class Rejected(val cause: ValueFlowRejection) : ValueModelSiteRead

    data class ContractRejected(val cause: ValueSiteRevalidationFailure) : ValueModelSiteRead

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : ValueModelSiteRead

    data class Limited(val cause: ValueSiteRevalidationLimit) : ValueModelSiteRead
}
