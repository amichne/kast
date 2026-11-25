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
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression

/** One native call-site discovery, retaining exact sites and confirming only the unconsumed suffix. */
internal class IntellijCalleeInventory(
    private val locators: IntellijRelationLocators,
    private val projection: IntellijK2RelationProjection,
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
                    else collectSite(element, subject, inventory)
                },
            )
        return inventory.finish(exhausted, RelationProviderState::callees)
    }

    private fun collectSite(
        element: PsiElement,
        subject: KtNamedDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
    ): Boolean {
        val ordinary =
            when (element) {
                is KtCallElement -> collect(element, subject, inventory)
                is KtCallableReferenceExpression -> collectReference(element, subject, inventory)
                is KtOperationReferenceExpression -> !element.isInfixName() || collectInfix(element, subject, inventory)
                is KtNameReferenceExpression -> collectJavaProperty(element, subject, inventory)
                else -> true
            }
        return ordinary && collectConvention(element, subject, inventory)
    }

    /** Only a name that K2 resolves to a Java property spends candidate capacity. */
    private fun collectJavaProperty(
        site: KtNameReferenceExpression,
        subject: KtNamedDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
    ): Boolean {
        val owner = site.nearestDeclaration()
        if (owner.enclosing()?.declaration !== subject) return true
        return collectRoles(site.mainReference, projection.javaAccessorRoles(site), owner, inventory)
    }

    private fun collectInfix(
        operation: KtOperationReferenceExpression,
        subject: KtNamedDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
    ): Boolean = collectReferences(operation, subject, inventory, operation.conventionReferences())

    private fun collectConvention(
        site: PsiElement,
        subject: KtNamedDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
    ): Boolean {
        val roles = site.conventionRoles()
        if (roles.isEmpty()) return true
        val owner = site.nearestDeclaration()
        if (owner.enclosing()?.declaration !== subject) return true
        val references = site.conventionReferences()
        // Every call expression is a possible invoke site, so a missing invoke reference is not a missing call.
        if (references.isEmpty()) return site is KtCallExpression || unresolvedSite()
        return references.all { reference -> collectRoles(reference, roles, owner, inventory) }
    }

    private fun collectRoles(
        reference: KtReference,
        roles: List<ConventionCallRole>,
        owner: ContainingDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
    ): Boolean = roles.all { role ->
        collector.admitProviderCandidate() == IntellijRelationProviderEnumerationAdmission.READY &&
            inventory.append(locators.callee(CalleeProviderItem.Convention(reference, role, owner)))
    }

    private fun unresolvedSite(): Boolean {
        collector.blockPartition(RelationLimitation.UNRESOLVED_TARGET)
        return false
    }

    private fun ContainingDeclaration.enclosing(): ContainingDeclaration.Found? =
        when (this) {
            is ContainingDeclaration.Deferred -> enclosingDeclaration() as? ContainingDeclaration.Found
            is ContainingDeclaration.Found -> this
            ContainingDeclaration.Unsupported -> null
        }

    private fun collectReference(
        expression: KtCallableReferenceExpression,
        subject: KtNamedDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
    ): Boolean =
        collectReferences(
            expression,
            subject,
            inventory,
            expression.callableReference.references.filterIsInstance<KtReference>(),
        )

    private fun collectReferences(
        site: PsiElement,
        subject: KtNamedDeclaration,
        inventory: IntellijRelationInventory<RelationProviderLocator.Callee>,
        references: List<KtReference>,
    ): Boolean {
        val owner = site.nearestDeclaration()
        if (owner.enclosing()?.declaration !== subject) return true
        if (references.isEmpty()) return unresolvedSite()
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
        if (owner.enclosing()?.declaration !== subject) return true
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
