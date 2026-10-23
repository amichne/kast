package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiReference
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtFunction

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
                is SupportedContainingDeclaration.Found -> {
                    if (isNamedLibraryInlineArgument(reference)) {
                        observation.count(IntellijReadCounter.CALLBACK_RESULTS_EXCLUDED)
                        return collector.dismissProviderItem()
                    }
                    NativeCallbackPolicy.Inline
                }
                is SupportedContainingDeclaration.Excluded -> NativeCallbackPolicy.Excluded(owner.evidence)
                SupportedContainingDeclaration.Unresolved ->
                    NativeCallbackPolicy.Unavailable(CallOwnershipFailure.UnresolvedArgumentMapping)
                SupportedContainingDeclaration.Unsupported ->
                    NativeCallbackPolicy.Unavailable(CallOwnershipFailure.UnsupportedBoundary)
            }
        return callbacks.retain(reference, plan.subject, policy)
    }

    /** An excluded named supplier's inline binary argument has no imported-source formal invocation site. */
    private fun isNamedLibraryInlineArgument(reference: PsiReference): Boolean {
        val deferred = reference.element.nearestDeclaration() as? ContainingDeclaration.Deferred ?: return false
        val literal = deferred.boundary as? KtFunction ?: return false
        val supply = classifyCallbackFunctionSupply(literal) as? IntellijCallbackLambdaSupply.Argument ?: return false
        val call = supplyingCallbackCall(supply.argument) ?: return false
        if (call.nearestDeclaration() !is ContainingDeclaration.Found) return false
        return when (val mapped = nativeArgumentBinding(call, supply.expression, observation)) {
            is Refinement.Refined -> mapped.value.origin == KaSymbolOrigin.LIBRARY
            is Refinement.Rejected -> false
        }
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
