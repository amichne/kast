package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** One native call-site discovery, retaining exact sites and confirming only the unconsumed suffix. */
internal class IntellijCalleeInventory(
    private val locators: IntellijRelationLocators,
    private val collector: IntellijRelationCollector,
    private val cancellationCheck: () -> Unit,
    private val limits: ReadLimits,
    private val observation: IntellijReadObservation,
) {
    fun prepare(subject: PsiNamedElement): RelationInventoryPreparation {
        if (subject !is KtNamedDeclaration) {
            collector.blockPartition(RelationLimitation.UNSUPPORTED_ITEM)
            return RelationInventoryPreparation.Unavailable
        }
        observation.phase(IntellijReadPhase.CALLEE_INVENTORY)
        val inventory = IntellijRelationInventory<RelationProviderLocator.Callee>(collector, limits, observation)
        val exhausted =
            PsiTreeUtil.processElements(
                subject,
                PsiElementProcessor<PsiElement> { element ->
                    cancellationCheck()
                    if (collector.admitProviderEnumeration() != IntellijRelationProviderEnumerationAdmission.READY)
                        false
                    else
                        when (element) {
                            is KtCallElement -> collect(element, subject, inventory)
                            is KtCallableReferenceExpression -> collectReference(element, subject, inventory)
                            else -> true
                        }
                },
            )
        return inventory.finish(exhausted, RelationProviderState::callees)
    }

    private fun collectReference(
        expression: KtCallableReferenceExpression,
        subject: KtNamedDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
    ): Boolean {
        val owner = expression.nearestDeclaration()
        val enclosing =
            when (owner) {
                is ContainingDeclaration.Deferred -> owner.enclosingDeclaration()
                is ContainingDeclaration.Found,
                ContainingDeclaration.Unsupported -> owner
            }
        if (enclosing !is ContainingDeclaration.Found || enclosing.declaration !== subject) return true
        val references = expression.callableReference.references.filterIsInstance<KtReference>()
        if (references.isEmpty()) {
            collector.blockPartition(RelationLimitation.UNRESOLVED_TARGET)
            return false
        }
        return references.all { reference ->
            collector.admitProviderCandidate() == IntellijRelationProviderEnumerationAdmission.READY &&
                inventory.append(locators.callee(CalleeProviderItem.Reference(reference, owner)))
        }
    }

    private fun collect(
        call: KtCallElement,
        subject: KtNamedDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
    ): Boolean {
        val owner = call.nearestDeclaration()
        val enclosing =
            when (owner) {
                is ContainingDeclaration.Deferred -> owner.enclosingDeclaration()
                is ContainingDeclaration.Found,
                ContainingDeclaration.Unsupported -> owner
            }
        if (enclosing !is ContainingDeclaration.Found || enclosing.declaration !== subject) return true
        if (collector.admitProviderCandidate() != IntellijRelationProviderEnumerationAdmission.READY) return false
        if (
            owner is ContainingDeclaration.Found &&
                !inventory.append(locators.callee(CalleeProviderItem.CallbackSupplies(call, owner)))
        )
            return false
        return when (val references = call.calleeReferences()) {
            is KotlinCallReferences.Found ->
                references.references.all { reference ->
                    collector.admitProviderCandidate() == IntellijRelationProviderEnumerationAdmission.READY &&
                        inventory.append(locators.callee(CalleeProviderItem.Reference(reference, owner)))
                }
            KotlinCallReferences.Unresolved ->
                collector.admitProviderCandidate() == IntellijRelationProviderEnumerationAdmission.READY &&
                    inventory.append(locators.callee(CalleeProviderItem.Unresolved(call, owner)))
        }
    }
}
