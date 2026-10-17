package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiReference
import io.github.amichne.kast.relation.contract.RelationLimitation
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression

/** Auxiliary references can prove callback invocation sites. They cannot emit ordinary named caller edges. */
internal class IntellijCallbackCallerProjection(
    private val projection: IntellijK2RelationProjection,
    private val callbacks: IntellijCallbackObservationEmitter,
    private val callables: IntellijCallableObservationEmitter,
    private val collector: IntellijRelationCollector,
    private val observation: io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation,
) {
    fun read(plan: IntellijRelationPlan.References, reference: PsiReference): Boolean {
        val kotlin =
            reference as? KtReference ?: return collector.examineIncomplete(RelationLimitation.UNSUPPORTED_ITEM)
        if ((reference.element.parent as? KtCallableReferenceExpression)?.callableReference === reference.element)
            return callables.retainNamedReference(
                CalleeProviderItem.Reference(kotlin, reference.element.nearestDeclaration())
            )
        val admitted =
            when (val admission = plan.admit(kotlin, observation)) {
                IntellijRelationReferenceAdmission.Skipped -> return collector.dismissProviderItem()
                is IntellijRelationReferenceAdmission.Admitted -> admission
            }
        when (projection.confirmTarget(admitted).observedBy(observation)) {
            IntellijK2TargetConfirmation.DIFFERENT_SYMBOL -> return collector.dismissProviderItem()
            IntellijK2TargetConfirmation.UNRESOLVED ->
                return collector.examineIncomplete(RelationLimitation.UNRESOLVED_TARGET)
            IntellijK2TargetConfirmation.EXACT_SUBJECT -> Unit
        }
        val policy =
            when (val owner = reference.element.nearestSupportedCallable(projection)) {
                is SupportedContainingDeclaration.Found -> NativeCallbackPolicy.Inline
                is SupportedContainingDeclaration.Excluded -> NativeCallbackPolicy.Excluded(owner.evidence)
                SupportedContainingDeclaration.Unresolved ->
                    NativeCallbackPolicy.Unavailable(CallOwnershipFailure.UnresolvedArgumentMapping)
                SupportedContainingDeclaration.Unsupported ->
                    NativeCallbackPolicy.Unavailable(CallOwnershipFailure.UnsupportedBoundary)
            }
        return callbacks.retain(reference, plan.subject, policy)
    }

    companion object {
        /** Only native reference shape and lexical containment run before candidate capacity. */
        fun isCandidate(reference: PsiReference): Boolean =
            reference is KtReference &&
                (reference.element.parent is KtCallableReferenceExpression ||
                    reference.element.nearestDeclaration() is ContainingDeclaration.Deferred) ||
                generateSequence(reference.element) { it.parent }
                    .any { element ->
                        element is com.intellij.psi.PsiLambdaExpression ||
                            element is com.intellij.psi.PsiMethodReferenceExpression
                    }
    }
}
