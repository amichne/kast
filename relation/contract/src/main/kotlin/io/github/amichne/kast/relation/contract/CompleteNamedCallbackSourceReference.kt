package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** A source reference preserves exact target identity without a claim about its later invocation. */
class CompleteNamedCallbackSourceReference
private constructor(val observation: RelationCallableObservation, val membership: CallbackResultMembership) {
    companion object {
        fun admit(
            observation: RelationCallableObservation
        ): Refinement<CompleteNamedCallbackSourceReference, StaticCallbackGraphFailure> {
            val target =
                observation.target as? RelationCallableTarget.NamedReference
                    ?: return Refinement.Rejected(StaticCallbackGraphFailure.NonCallableTarget)
            val membership =
                when (val projection = target.resultProjection) {
                    is CallbackResultProjection.Admitted -> projection.membership
                    is CallbackResultProjection.Unproven ->
                        return Refinement.Rejected(StaticCallbackGraphFailure.Unavailable(projection.cause))
                }
            when (val flow = target.reference.flow) {
                is NamedCallbackReferenceFlow.Unavailable ->
                    when (val admitted = sourceFlowAdmission(flow.causes, CallbackInvocationScan.INCOMPLETE)) {
                        is Refinement.Rejected -> return admitted
                        is Refinement.Refined -> Unit
                    }
                is NamedCallbackReferenceFlow.Immutable ->
                    when (val admitted = sourceFlowAdmission(flow.flow.obligations, flow.flow.scan)) {
                        is Refinement.Rejected -> return admitted
                        is Refinement.Refined -> Unit
                    }
                is NamedCallbackReferenceFlow.Direct,
                is NamedCallbackReferenceFlow.Supplied -> Unit
            }
            val files =
                listOf(
                    observation.occurrence.file,
                    observation.body.file,
                    observation.lexicalOwner.file,
                    target.reference.occurrence.file,
                    target.reference.target.file,
                )
            if (files.any { !it.insideCallbackWorkspace(observation.basis.workspaceRoot) })
                return Refinement.Rejected(StaticCallbackGraphFailure.OutsideWorkspace)
            return Refinement.Refined(CompleteNamedCallbackSourceReference(observation, membership))
        }
    }
}
