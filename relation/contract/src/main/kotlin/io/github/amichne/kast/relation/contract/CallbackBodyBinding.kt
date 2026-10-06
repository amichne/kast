package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import java.util.Collections

sealed interface CallbackBodySupply {
    data class Invocation(val occurrence: RelationOccurrence) : CallbackBodySupply

    data class DefaultParameter(val parameter: RelationOccurrence) : CallbackBodySupply

    data class DirectInvocation(val occurrence: RelationOccurrence) : CallbackBodySupply

    data object Stored : CallbackBodySupply

    data class Returned(val occurrence: RelationOccurrence) : CallbackBodySupply

    data object Unsupported : CallbackBodySupply
}

/** Exact nested-body supply and formal mapping; supplying a body does not establish its activation. */
@ConsistentCopyVisibility
data class CallbackBodyBinding
private constructor(
    val body: RelationCallableBody.Anonymous,
    val supply: CallbackBodySupply,
    val binding: CallbackBindingEvidence,
    val obligations: Set<CallbackInvocationFlowCause>,
) {
    val retainedBytes: Long
        get() = 4096L + canonicalProjection().toByteArray(Charsets.UTF_8).size * 4L

    companion object {
        fun fromCompiler(
            body: RelationCallableBody.Anonymous,
            supply: CallbackBodySupply,
            binding: CallbackBindingEvidence,
            obligations: Set<CallbackInvocationFlowCause>,
        ): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
            if (CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION !in obligations)
                return Refinement.Rejected(CallbackInvocationFlowFailure.MISSING_OBLIGATION)
            when (val admitted = admitBodySupply(body, supply, binding, obligations)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(
                CallbackBodyBinding(body, supply, binding, Collections.unmodifiableSet(obligations.toSet()))
            )
        }
    }
}

private fun admitBodySupply(
    body: RelationCallableBody.Anonymous,
    supply: CallbackBodySupply,
    evidence: CallbackBindingEvidence,
    obligations: Set<CallbackInvocationFlowCause>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val admitted =
        when (supply) {
            is CallbackBodySupply.DefaultParameter -> admitDefaultSupply(body, supply, evidence)
            is CallbackBodySupply.DirectInvocation -> admitDirectSupply(body, supply, evidence)
            is CallbackBodySupply.Invocation -> admitInvocationSupply(body, supply, evidence)
            CallbackBodySupply.Stored -> admitUnavailableBinding(evidence, CallbackInvocationFlowCause.STORED_CALLBACK)
            is CallbackBodySupply.Returned -> admitReturnedSupply(body, supply, evidence)
            CallbackBodySupply.Unsupported ->
                admitUnavailableBinding(evidence, CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        }
    when (admitted) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    if (evidence is CallbackBindingEvidence.Unavailable && evidence.cause !in obligations)
        return Refinement.Rejected(CallbackInvocationFlowFailure.MISSING_OBLIGATION)
    return Refinement.Refined(Unit)
}

private fun admitDefaultSupply(
    body: RelationCallableBody.Anonymous,
    supply: CallbackBodySupply.DefaultParameter,
    evidence: CallbackBindingEvidence,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (!supply.parameter.containsBody(body))
        return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    return when (evidence) {
        is CallbackBindingEvidence.Default ->
            if (
                evidence.binding.parameter.parameter == supply.parameter &&
                    evidence.binding.defaultValue.containsBody(body)
            )
                Refinement.Refined(Unit)
            else Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        is CallbackBindingEvidence.Unavailable -> Refinement.Refined(Unit)
        is CallbackBindingEvidence.Direct,
        is CallbackBindingEvidence.Bound -> Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    }
}

private fun admitDirectSupply(
    body: RelationCallableBody.Anonymous,
    supply: CallbackBodySupply.DirectInvocation,
    evidence: CallbackBindingEvidence,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (!supply.occurrence.containsBody(body))
        return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    return when (evidence) {
        is CallbackBindingEvidence.Direct ->
            if (evidence.binding.occurrence == supply.occurrence) Refinement.Refined(Unit)
            else Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        is CallbackBindingEvidence.Unavailable -> Refinement.Refined(Unit)
        is CallbackBindingEvidence.Default,
        is CallbackBindingEvidence.Bound -> Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    }
}

private fun admitInvocationSupply(
    body: RelationCallableBody.Anonymous,
    supply: CallbackBodySupply.Invocation,
    evidence: CallbackBindingEvidence,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (!supply.occurrence.containsBody(body))
        return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
    if (evidence is CallbackBindingEvidence.Default || evidence is CallbackBindingEvidence.Direct)
        return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    if (evidence is CallbackBindingEvidence.Bound && !evidence.binding.matchesSupply(supply))
        return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun admitReturnedSupply(
    body: RelationCallableBody.Anonymous,
    supply: CallbackBodySupply.Returned,
    evidence: CallbackBindingEvidence,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (!supply.occurrence.containsBody(body))
        return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
    return admitUnavailableBinding(evidence, CallbackInvocationFlowCause.RETURNED_CALLBACK)
}

private fun admitUnavailableBinding(
    evidence: CallbackBindingEvidence,
    cause: CallbackInvocationFlowCause,
): Refinement<Unit, CallbackInvocationFlowFailure> =
    if (evidence == CallbackBindingEvidence.Unavailable(cause)) Refinement.Refined(Unit)
    else Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)

private fun RelationOccurrence.containsBody(body: RelationCallableBody.Anonymous): Boolean =
    file == body.file && range.containsValueRange(body.range)

private fun CallbackArgumentBinding.matchesSupply(supply: CallbackBodySupply.Invocation): Boolean =
    invocation.callable.file is SymbolDiscoveryFileIdentity.Workspace &&
        invocation.enclosing.file == supply.occurrence.file &&
        invocation.range == supply.occurrence.range
