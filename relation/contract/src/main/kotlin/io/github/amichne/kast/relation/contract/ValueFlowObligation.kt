package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface ValueFlowObligationEvidence {
    data object None : ValueFlowObligationEvidence

    data class ImplementationDispatch internal constructor(val dispatch: ValueImplementationDispatch) :
        ValueFlowObligationEvidence
}

enum class ValueFlowObligationFailure {
    BASIS_MISMATCH,
    SOURCE_MISMATCH,
}

/** Unresolved target-set evidence cannot confer a positive transfer or a terminal model. */
@ConsistentCopyVisibility
data class ValueFlowObligation
private constructor(
    val site: ValueSite,
    val cause: ValueFlowUnsupportedCause,
    val evidence: ValueFlowObligationEvidence,
) {
    constructor(site: ValueSite, cause: ValueFlowUnsupportedCause) : this(site, cause, ValueFlowObligationEvidence.None)

    internal fun shareCallableEvidence(shareSite: (ValueSite) -> ValueSite): ValueFlowObligation {
        val shared = shareSite(site)
        return if (shared === site) this else ValueFlowObligation(shared, cause, evidence)
    }

    companion object {
        fun fromImplementation(
            site: ValueSite,
            cause: ValueFlowUnsupportedCause,
            dispatch: ValueImplementationDispatch,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
            val question = dispatch.batch.request
            if (site.basis != question.subject.lease.identity)
                return Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH)
            val matches =
                when (val role = site.role) {
                    is ValueRole.Argument -> role.call.callable.valueIdentity == question.subject.valueIdentity
                    is ValueRole.Receiver -> role.call.callable.valueIdentity == question.subject.valueIdentity
                    // A return can preserve an unresolved caller investigation without asserting its relationship.
                    ValueRole.Return -> true
                    is ValueRole.Formal,
                    ValueRole.ExpressionResult,
                    ValueRole.LocalBinding,
                    ValueRole.LocalRead,
                    ValueRole.PropertyAssignment -> false
                }
            return if (matches)
                Refinement.Refined(
                    ValueFlowObligation(site, cause, ValueFlowObligationEvidence.ImplementationDispatch(dispatch))
                )
            else Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH)
        }
    }
}
