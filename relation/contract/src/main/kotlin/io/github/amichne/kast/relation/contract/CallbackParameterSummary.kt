package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** Exhaustive detached formal-body evidence; a supplier binding is admitted separately on every use. */
@ConsistentCopyVisibility
data class CallbackParameterSummary
private constructor(
    val formal: CallbackParameterIdentity,
    val invocations: List<CallbackParameterInvocation>,
    val obligations: Set<CallbackInvocationFlowCause>,
    val ownerBindings: List<CallbackBodyBinding>,
    val scan: CallbackInvocationScan,
) {
    val retainedBytes: Long =
        invocations
            .fold(4096L.addBytes(formal.callable.detachedTextUnits().multiplyBytes(2))) { bytes, invocation ->
                bytes.addBytes(invocation.retainedBytes)
            }
            .let { invocationBytes ->
                ownerBindings.fold(invocationBytes) { bytes, binding -> bytes.addBytes(binding.retainedBytes) }
            }

    fun instantiate(
        body: RelationCallableBody.Anonymous,
        binding: CallbackArgumentBinding,
    ): Refinement<CallbackInvocationFlow, CallbackInvocationFlowFailure> =
        instantiate(body, CallbackBindingEvidence.Bound(binding))

    fun instantiate(
        body: RelationCallableBody.Anonymous,
        binding: CallbackBindingEvidence,
    ): Refinement<CallbackInvocationFlow, CallbackInvocationFlowFailure> {
        val suppliedFormal =
            when (binding) {
                is CallbackBindingEvidence.Bound -> {
                    if (binding.binding.invocation.basis != formal.callable.lease.identity)
                        return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
                    when (
                        val identity =
                            CallbackParameterIdentity.fromCompiler(
                                binding.binding.invocation.callable,
                                binding.binding.position,
                                binding.binding.parameter,
                            )
                    ) {
                        is Refinement.Refined -> identity.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
                    }
                }
                is CallbackBindingEvidence.Default -> binding.binding.parameter
                is CallbackBindingEvidence.Direct,
                is CallbackBindingEvidence.Unavailable ->
                    return Refinement.Rejected(CallbackInvocationFlowFailure.UNBOUND_INVOCATION)
            }
        if (suppliedFormal.callable.lease.identity != formal.callable.lease.identity)
            return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
        if (suppliedFormal != formal) return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
        val instanceObligations =
            if (callbackExecutionNeedsQualification(binding, invocations))
                obligations + CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
            else obligations
        return when (
            val flow =
                CallbackInvocationFlow.fromCompiler(
                    formal.callable.lease.identity,
                    body,
                    binding,
                    invocations,
                    instanceObligations,
                    scan,
                )
        ) {
            is Refinement.Refined -> flow.value.withOwnerBindings(ownerBindings)
            is Refinement.Rejected -> flow
        }
    }

    companion object {
        fun fromCompiler(
            formal: CallbackParameterIdentity,
            invocations: List<CallbackParameterInvocation>,
            obligations: Set<CallbackInvocationFlowCause>,
            ownerBindings: List<CallbackBodyBinding> = emptyList(),
            scan: CallbackInvocationScan,
        ): Refinement<CallbackParameterSummary, CallbackInvocationFlowFailure> {
            if (scan != CallbackInvocationScan.EXHAUSTIVE || !admitsExhaustiveCallbackScan(obligations))
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_SCAN_PROOF)
            if (invocations.distinct().size != invocations.size)
                return Refinement.Rejected(CallbackInvocationFlowFailure.DUPLICATE_INVOCATION)
            when (
                val admitted =
                    admitInvocationRoutes(
                        formal.callable.lease.identity,
                        formal.callable,
                        formal.position,
                        formal.parameter,
                        invocations,
                    )
            ) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            if (
                callbackInvocationsNeedQualification(formal.callable, invocations) &&
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION !in obligations
            )
                return Refinement.Rejected(CallbackInvocationFlowFailure.MISSING_OBLIGATION)
            val owners =
                invocations.map { it.owner } + invocations.flatMap { it.forwardings.map { it.target.invocationOwner } }
            when (
                val admitted = admitCallbackOwnerBindings(formal.callable.lease.identity, scan, owners, ownerBindings)
            ) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(
                CallbackParameterSummary(
                    formal,
                    Collections.unmodifiableList(invocations.toList()),
                    Collections.unmodifiableSet(obligations.toSet()),
                    Collections.unmodifiableList(ownerBindings.toList()),
                    scan,
                )
            )
        }
    }
}
