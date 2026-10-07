package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.Collections

enum class CallbackInvocationFlowCause {
    STORED_CALLBACK,
    RETURNED_CALLBACK,
    UNSUPPORTED_CALLBACK_SUPPLY,
    ANONYMOUS_IDENTITY_UNAVAILABLE,
    UNRESOLVED_ARGUMENT_MAPPING,
    UNRESOLVED_PARAMETER_REFERENCE,
    EXTERNAL_CALLABLE,
    OUTSIDE_DOMAIN,
    PARAMETER_ESCAPES,
    CALLBACK_CYCLE,
    NESTED_CALLBACK_EXECUTION,
    NO_INVOCATION_PROVEN,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
}

sealed interface CallbackBindingEvidence {
    data class Bound(val binding: CallbackArgumentBinding) : CallbackBindingEvidence

    data class Default(val binding: CallbackDefaultBinding) : CallbackBindingEvidence

    data class Direct(val binding: CallbackDirectInvocationBinding) : CallbackBindingEvidence

    data class Unavailable(val cause: CallbackInvocationFlowCause) : CallbackBindingEvidence
}

/** Possible static invocation evidence; neither mapping nor containment establishes runtime execution. */
@ConsistentCopyVisibility
data class CallbackInvocationFlow
private constructor(
    val basis: SemanticReadIdentity,
    val body: RelationCallableBody.Anonymous,
    val binding: CallbackBindingEvidence,
    val invocations: List<CallbackParameterInvocation>,
    val obligations: Set<CallbackInvocationFlowCause>,
    val ownerBindings: List<CallbackBodyBinding> = emptyList(),
    val scan: CallbackInvocationScan = CallbackInvocationScan.INCOMPLETE,
    val forwarding: CallbackForwardingEvidence = CallbackForwardingEvidence.InvocationRoutes,
) {
    /** Conservative detached proof storage; wire presentation retains its independent encoded byte guard. */
    val retainedBytes: Long =
        4096L
            .addBytes(canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong().multiplyBytes(4))
            .addBytes(
                when (binding) {
                    is CallbackBindingEvidence.Bound -> valueSiteStorageBytes(binding.binding.invocation.resultSite())
                    is CallbackBindingEvidence.Default,
                    is CallbackBindingEvidence.Direct,
                    is CallbackBindingEvidence.Unavailable -> 256L
                }
            )
            .let { bytes ->
                when (forwarding) {
                    CallbackForwardingEvidence.InvocationRoutes -> bytes
                    is CallbackForwardingEvidence.ExhaustedGraph -> bytes.addBytes(forwarding.graph.retainedBytes)
                }
            }

    /** Refines actual anonymous-owner facts without discarding an existing binding or activation obligation. */
    fun withOwnerBindings(
        additional: List<CallbackBodyBinding>
    ): Refinement<CallbackInvocationFlow, CallbackInvocationFlowFailure> {
        val combined = ownerBindings + additional
        when (val admitted = admitCallbackOwnerBindings(this, combined)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
        return Refinement.Refined(
            CallbackInvocationFlow(
                basis = basis,
                body = body,
                binding = binding,
                invocations = invocations,
                obligations = Collections.unmodifiableSet(obligations + combined.flatMap { it.obligations }),
                ownerBindings = Collections.unmodifiableList(combined.toList()),
                scan = scan,
                forwarding = forwarding,
            )
        )
    }

    companion object {
        fun fromCompiler(
            basis: SemanticReadIdentity,
            body: RelationCallableBody.Anonymous,
            binding: CallbackBindingEvidence,
            invocations: List<CallbackParameterInvocation>,
            obligations: Set<CallbackInvocationFlowCause>,
            scan: CallbackInvocationScan = CallbackInvocationScan.INCOMPLETE,
            forwarding: CallbackForwardingEvidence = CallbackForwardingEvidence.InvocationRoutes,
        ): Refinement<CallbackInvocationFlow, CallbackInvocationFlowFailure> {
            val admitted = admitCallbackFlowEvidence(basis, body, binding, invocations, obligations)
            when (admitted) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            if (scan == CallbackInvocationScan.EXHAUSTIVE && !admitsExhaustiveCallbackScan(obligations))
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_SCAN_PROOF)
            if (invocations.distinct().size != invocations.size)
                return Refinement.Rejected(CallbackInvocationFlowFailure.DUPLICATE_INVOCATION)
            when (val graph = admitCallbackForwardingEvidence(binding, scan, invocations, forwarding, obligations)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return graph
            }
            when (val scanProof = admitCallbackScan(binding, scan, invocations, obligations)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return scanProof
            }
            if (
                (callbackExecutionNeedsQualification(binding, invocations) || forwarding.needsQualification()) &&
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION !in obligations
            )
                return Refinement.Rejected(CallbackInvocationFlowFailure.MISSING_OBLIGATION)
            return Refinement.Refined(
                CallbackInvocationFlow(
                    basis = basis,
                    body = body,
                    binding = binding,
                    invocations = Collections.unmodifiableList(invocations.toList()),
                    obligations = Collections.unmodifiableSet(obligations.toSet()),
                    scan = scan,
                    forwarding = forwarding,
                )
            )
        }
    }
}

internal fun CallbackForwardingEvidence.needsQualification(): Boolean =
    when (this) {
        CallbackForwardingEvidence.InvocationRoutes -> false
        is CallbackForwardingEvidence.ExhaustedGraph ->
            graph.forwardings.any {
                val owner = it.target.invocationOwner
                owner !is RelationCallableBody.Named || owner.compilerIdentity != it.source.callable.compilerIdentity
            }
    }

enum class CallbackInvocationFlowFailure {
    BASIS_MISMATCH,
    BODY_OUTSIDE_ARGUMENT,
    PARAMETER_OUTSIDE_CALLABLE,
    INVALID_PARAMETER_POSITION,
    INVOCATION_OUTSIDE_CALLABLE,
    INVOCATION_OUTSIDE_OWNER,
    UNBOUND_INVOCATION,
    DUPLICATE_INVOCATION,
    MISSING_OBLIGATION,
    UNSUPPORTED_CALLABLE_TRANSFER,
    INVALID_CALLABLE_TRANSFER_PATH,
    CALLABLE_TRANSFER_BINDING_MISMATCH,
    INVOCATION_OUTSIDE_SUPPLYING_OWNER,
    OWNER_BINDING_MISMATCH,
    DUPLICATE_OWNER_BINDING,
    INVALID_SCAN_PROOF,
    INVALID_FORWARDING_PATH,
}

sealed interface CallbackInvocationFlowRead {
    data class Immutable(val flow: ImmutableCallbackInvocationFlow) : CallbackInvocationFlowRead

    data class Observed(val flow: CallbackInvocationFlow) : CallbackInvocationFlowRead

    data class Unavailable(val cause: CallbackInvocationFlowCause) : CallbackInvocationFlowRead

    data class ContractRejected(val cause: CallbackInvocationFlowFailure) : CallbackInvocationFlowRead
}
