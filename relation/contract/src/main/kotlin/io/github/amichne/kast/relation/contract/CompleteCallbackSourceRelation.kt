package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Exact source identity and result membership, independent of callback execution completeness. */
class CompleteCallbackSourceRelation
private constructor(val observation: RelationCallbackObservation, val membership: CallbackResultMembership) {
    companion object {
        fun admit(
            observation: RelationCallbackObservation
        ): Refinement<CompleteCallbackSourceRelation, StaticCallbackGraphFailure> {
            val membership =
                when (val projection = observation.resultProjection) {
                    is CallbackResultProjection.Admitted -> projection.membership
                    is CallbackResultProjection.Unproven ->
                        return Refinement.Rejected(StaticCallbackGraphFailure.Unavailable(projection.cause))
                }
            val policy = observation.policy
            if (policy is CallbackNamedCallPolicy.Unavailable)
                return Refinement.Rejected(StaticCallbackGraphFailure.UnprovenPolicy(policy))
            when (val read = observation.flow) {
                is CallbackInvocationFlowRead.ContractRejected ->
                    return Refinement.Rejected(StaticCallbackGraphFailure.InvalidFlow(read.cause))
                is CallbackInvocationFlowRead.Unavailable ->
                    when (val admitted = read.cause.sourceAdmission()) {
                        is Refinement.Rejected -> return admitted
                        is Refinement.Refined -> Unit
                    }
                is CallbackInvocationFlowRead.Observed ->
                    when (val admitted = sourceFlowAdmission(read.flow.obligations, read.flow.scan)) {
                        is Refinement.Rejected -> return admitted
                        is Refinement.Refined -> Unit
                    }
                is CallbackInvocationFlowRead.Immutable ->
                    when (val admitted = sourceFlowAdmission(read.flow.obligations, read.flow.scan)) {
                        is Refinement.Rejected -> return admitted
                        is Refinement.Refined -> Unit
                    }
            }
            val files =
                listOf(
                    observation.occurrence.file,
                    observation.callbackBody.file,
                    observation.lexicalOwner.file,
                    observation.target.file,
                )
            if (files.any { !it.insideCallbackWorkspace(observation.basis.workspaceRoot) })
                return Refinement.Rejected(StaticCallbackGraphFailure.OutsideWorkspace)
            return Refinement.Refined(CompleteCallbackSourceRelation(observation, membership))
        }
    }
}

/** Unknown execution effects remain attached to the admitted source observation. */
internal fun sourceFlowAdmission(
    obligations: Set<CallbackInvocationFlowCause>,
    scan: CallbackInvocationScan,
): Refinement<Unit, StaticCallbackGraphFailure> {
    for (cause in obligations) when (val admitted = cause.sourceAdmission()) {
        is Refinement.Rejected -> return admitted
        is Refinement.Refined -> Unit
    }
    if (scan == CallbackInvocationScan.INCOMPLETE && obligations.isEmpty())
        return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
    return Refinement.Refined(Unit)
}

/** Execution gaps cannot discharge unresolved identity, stale evidence, or resource exhaustion. */
internal fun CallbackInvocationFlowCause.sourceAdmission(): Refinement<Unit, StaticCallbackGraphFailure> =
    when (this) {
        CallbackInvocationFlowCause.FINALLY_UNSUPPORTED,
        CallbackInvocationFlowCause.ABRUPT_COMPLETION,
        CallbackInvocationFlowCause.STORED_CALLBACK,
        CallbackInvocationFlowCause.RETURNED_CALLBACK,
        CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
        CallbackInvocationFlowCause.EXTERNAL_CALLABLE,
        CallbackInvocationFlowCause.OUTSIDE_DOMAIN,
        CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN,
        CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN,
        CallbackInvocationFlowCause.PARAMETER_ESCAPES,
        CallbackInvocationFlowCause.CALLBACK_CYCLE,
        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
        CallbackInvocationFlowCause.NO_INVOCATION_PROVEN -> Refinement.Refined(Unit)
        CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING,
        CallbackInvocationFlowCause.SUPPLIER_TARGET_MISMATCH,
        CallbackInvocationFlowCause.SUPPLIER_INVOCATION_UNAVAILABLE,
        CallbackInvocationFlowCause.SUPPLIER_INVOCATION_MISMATCH,
        CallbackInvocationFlowCause.SUPPLIER_BINDING_REJECTED,
        CallbackInvocationFlowCause.SUPPLIER_ARGUMENT_UNAVAILABLE,
        CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE,
        CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE,
        CallbackInvocationFlowCause.DEPENDENCY_BINARY_CHANGED,
        CallbackInvocationFlowCause.DEPENDENCY_TARGET_UNAVAILABLE,
        CallbackInvocationFlowCause.WORK_LIMIT_REACHED,
        CallbackInvocationFlowCause.TIME_LIMIT_REACHED,
        CallbackInvocationFlowCause.RESULT_LIMIT_REACHED,
        CallbackInvocationFlowCause.BYTE_LIMIT_REACHED ->
            Refinement.Rejected(StaticCallbackGraphFailure.Unavailable(this))
    }
