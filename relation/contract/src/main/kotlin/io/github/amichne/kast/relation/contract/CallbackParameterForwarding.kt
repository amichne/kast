package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Retains the exact formal read supplied to the destination formal; containment alone is not value identity. */
@ConsistentCopyVisibility
data class CallbackParameterForwarding
private constructor(
    val source: CallbackParameterIdentity,
    val argument: RelationOccurrence,
    val target: CallbackArgumentBinding,
) {
    companion object {
        fun fromCompiler(
            source: CallbackParameterIdentity,
            argument: RelationOccurrence,
            target: CallbackArgumentBinding,
        ): Refinement<CallbackParameterForwarding, CallbackInvocationFlowFailure> =
            when {
                source.callable.lease.identity != target.invocation.basis ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
                source.callable.valueIdentity != target.invocation.enclosing.valueIdentity ||
                    argument.file != source.callable.file ||
                    !target.invocation.range.containsValueRange(argument.range) ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
                else -> Refinement.Refined(CallbackParameterForwarding(source, argument, target))
            }
    }
}
