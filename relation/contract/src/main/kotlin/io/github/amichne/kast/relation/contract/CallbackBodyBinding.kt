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

sealed interface CallbackOwnerExecution {
    val retainedBytes: Long
        get() =
            when (this) {
                Unproven -> 0L
                is Observed -> flow.retainedBytes
                is Immutable -> flow.retainedBytes
            }

    data object Unproven : CallbackOwnerExecution

    data class Observed internal constructor(val flow: CallbackInvocationFlow) : CallbackOwnerExecution

    data class Immutable
    internal constructor(
        val flow: ImmutableCallbackInvocationFlow,
        val owner: RelationCallableBody.Named,
    ) : CallbackOwnerExecution
}

/** Exact nested-body supply and formal mapping; supplying a body does not establish its activation. */
@ConsistentCopyVisibility
data class CallbackBodyBinding
private constructor(
    val body: RelationCallableBody.Anonymous,
    val supply: CallbackBodySupply,
    val binding: CallbackBindingEvidence,
    val obligations: Set<CallbackInvocationFlowCause>,
    val execution: CallbackOwnerExecution = CallbackOwnerExecution.Unproven,
) {
    val retainedBytes: Long =
        4096L
            .addBytes(
                canonicalProjection(includeOwnerExecution = false)
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong()
                    .multiplyBytes(4)
            )
            .addBytes(execution.retainedBytes)

    /** Attaches an exact owner read; only a complete route discharges the nested execution obligation. */
    fun withExecution(flow: CallbackInvocationFlow): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
        if (flow.body != body || flow.binding != binding)
            return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        val normalized =
            when (val admitted = flow.withOwnerBindings(emptyList())) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val combined = obligations + normalized.obligations
        val remaining =
            when (CompleteCallbackFlowGraph.admit(normalized)) {
                is Refinement.Refined -> combined - CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
                is Refinement.Rejected -> combined
            }
        return Refinement.Refined(
            CallbackBodyBinding(
                body,
                supply,
                binding,
                Collections.unmodifiableSet(remaining.toSet()),
                CallbackOwnerExecution.Observed(normalized),
            )
        )
    }

    fun withExecution(
        flow: ImmutableCallbackInvocationFlow,
        owner: RelationCallableBody.Named,
    ): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
        val unavailable =
            binding as? CallbackBindingEvidence.Unavailable
                ?: return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        if (unavailable.cause !in immutableSupplyCauses || flow.origin != ImmutableCallbackValueOrigin.Anonymous(body))
            return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        if (
            flow.source.enclosing.file != owner.file ||
                flow.source.enclosing.range != owner.range ||
                flow.source.enclosing.compilerIdentity != owner.compilerIdentity
        )
            return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        val combined = obligations + flow.obligations
        val remaining =
            when (CompleteCallbackFlowGraph.admit(flow, owner)) {
                is Refinement.Refined ->
                    combined - unavailable.cause - CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
                is Refinement.Rejected -> combined
            }
        return Refinement.Refined(
            CallbackBodyBinding(
                body,
                supply,
                binding,
                Collections.unmodifiableSet(remaining.toSet()),
                CallbackOwnerExecution.Immutable(flow, owner),
            )
        )
    }

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

private val immutableSupplyCauses =
    setOf(
        CallbackInvocationFlowCause.STORED_CALLBACK,
        CallbackInvocationFlowCause.RETURNED_CALLBACK,
        CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
    )

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
        is CallbackBindingEvidence.DeferredDependency,
        is CallbackBindingEvidence.DependencyContract,
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
        is CallbackBindingEvidence.DeferredDependency,
        is CallbackBindingEvidence.DependencyContract,
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
    if (evidence is CallbackBindingEvidence.DependencyContract && evidence.binding.occurrence != supply.occurrence)
        return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    if (evidence is CallbackBindingEvidence.DeferredDependency && evidence.binding.occurrence != supply.occurrence)
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
