package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCallbackInputFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueCallbackInputFailure

internal fun ValueCallbackInputFailure.impactDocument(): ImpactProjected<ImpactCallbackInputFailureDocument> =
    when (this) {
        is ValueCallbackInputFailure.ImplementationRejected ->
            Refinement.Refined(
                ImpactCallbackInputFailureDocument.ImplementationRejected(cause.impactImplementationFailure())
            )
        is ValueCallbackInputFailure.ImplementationIncomplete ->
            dispatch.impactDocument().impactMap(ImpactCallbackInputFailureDocument::ImplementationIncomplete)
        is ValueCallbackInputFailure.Callback ->
            Refinement.Refined(ImpactCallbackInputFailureDocument.Callback(cause.protocolCallbackDocument()))
        is ValueCallbackInputFailure.Contract ->
            Refinement.Refined(ImpactCallbackInputFailureDocument.Contract(cause.protocolCallbackDocument()))
        is ValueCallbackInputFailure.Graph -> {
            val reason =
                when (val projected = cause.protocolGraphCause()) {
                    is Refinement.Refined -> projected.value
                    is Refinement.Rejected -> QueryCallbackGraphCauseDocument.InvalidGraphFailure(projected.failure)
                }
            Refinement.Refined(
                if (cause is StaticCallbackGraphFailure.ImmutableUnresolved)
                    ImpactCallbackInputFailureDocument.ImmutableGraph(reason)
                else ImpactCallbackInputFailureDocument.Graph(reason)
            )
        }
    }

private fun io.github.amichne.kast.relation.contract.RelationCompilerRejection.impactImplementationFailure():
    io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument =
    when (this) {
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.WORKSPACE_ROOT_MISMATCH ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.WORKSPACE_ROOT_MISMATCH
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.GENERATION_MOVED ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.GENERATION_MOVED
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.SCOPE_REJECTED ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.SCOPE_REJECTED
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.WORKSPACE_INDEX_UNAVAILABLE
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.STALE_SELECTOR ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.STALE_SELECTOR
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.OUTSIDE_SCOPE ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.OUTSIDE_SCOPE
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.AMBIGUOUS_SUBJECT ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.AMBIGUOUS_SUBJECT
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.UNSUPPORTED_SUBJECT ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.UNSUPPORTED_SUBJECT
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.COMPILER_IDENTITY_UNAVAILABLE
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.CONTINUATION_CURSOR_MOVED ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.CONTINUATION_CURSOR_MOVED
        io.github.amichne.kast.relation.contract.RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION ->
            io.github.amichne.kast.protocol.contract.ImpactRelationCompilerFailureDocument.COMPILER_CONTRACT_VIOLATION
    }
