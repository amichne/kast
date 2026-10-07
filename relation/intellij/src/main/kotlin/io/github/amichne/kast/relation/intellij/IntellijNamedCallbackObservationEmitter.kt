package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression

internal class IntellijNamedCallbackObservationEmitter(
    private val context: IntellijCallbackFlowContext,
    private val projection: IntellijK2RelationProjection,
    private val collector: IntellijRelationCollector,
    private val incomplete: (CalleeProviderItem, RelationLimitation) -> Boolean,
    summaries: CallbackParameterSummaries,
) {
    private val request = context.scope.request
    private val observation = summaries.observation
    private val referenceReader = IntellijNamedCallbackReference(context, summaries)

    fun retain(candidate: CalleeProviderItem.Reference): Boolean {
        val expression =
            candidate.reference.element.parent as? org.jetbrains.kotlin.psi.KtCallableReferenceExpression
                ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val owner =
            when (val projected = owner(expression)) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return incomplete(candidate, projected.failure)
            }
        val reference =
            when (val read = referenceReader.read(expression, owner)) {
                is Refinement.Refined ->
                    read.value.also {
                        observation.count(
                            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                                .NAMED_CALLBACK_REFERENCES_CONFIRMED
                        )
                    }
                is Refinement.Rejected -> {
                    observation.count(
                        io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                            .NAMED_CALLBACK_REFERENCES_REJECTED
                    )
                    return unavailable(candidate, expression, owner, read.failure)
                }
            }
        if (
            request.meaning == io.github.amichne.kast.relation.contract.RelationMeaning.Callers &&
                io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint.validate(
                    request.subject,
                    reference.target.evidence,
                ) is Refinement.Rejected
        )
            return collector.dismissProviderItem()
        val body = context.owner(expression) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        return when (
            val admitted =
                RelationCallableObservation.fromNativeBoundary(
                    request,
                    reference.occurrence,
                    owner,
                    body,
                    RelationCallableTarget.NamedReference(reference),
                )
        ) {
            is Refinement.Refined -> collector.acceptCallableObservation(admitted.value)
            is Refinement.Rejected -> collector.blockPartition(RelationLimitation.PROVIDER_INCOMPLETE)
        }
    }

    private fun owner(
        expression: KtCallableReferenceExpression
    ): Refinement<CompilerGroundedSymbolEvidence, RelationLimitation> {
        val lexical =
            when (val owner = expression.nearestDeclaration()) {
                is ContainingDeclaration.Deferred -> owner.enclosingDeclaration()
                is ContainingDeclaration.Found,
                ContainingDeclaration.Unsupported -> owner
            }
                as? ContainingDeclaration.Found ?: return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        return when (val projected = projection.project(lexical.declaration)) {
            is IntellijRelationDeclarationProjection.Projected -> Refinement.Refined(projected.evidence)
            IntellijRelationDeclarationProjection.Unsupported ->
                return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        }
    }

    private fun unavailable(
        candidate: CalleeProviderItem.Reference,
        expression: KtCallableReferenceExpression,
        owner: CompilerGroundedSymbolEvidence,
        cause: CallbackInvocationFlowCause,
    ): Boolean {
        val occurrence =
            context.occurrence(expression) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val body = context.owner(expression) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        return when (
            val unavailable =
                RelationCallableObservation.fromNativeBoundary(
                    request,
                    occurrence,
                    owner,
                    body,
                    RelationCallableTarget.UnavailableReference(cause),
                )
        ) {
            is Refinement.Refined -> collector.acceptCallableObservation(unavailable.value)
            is Refinement.Rejected -> incomplete(candidate, RelationLimitation.PROVIDER_INCOMPLETE)
        }
    }
}
