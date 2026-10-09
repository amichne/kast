package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase

/** One authoritative ReferencesSearch under the original native universe; no PSI survives preparation. */
internal class IntellijReferenceInventory(
    private val scope: CompiledRelationScope,
    private val locators: IntellijRelationLocators,
    private val collector: IntellijRelationCollector,
    private val cancellationCheck: () -> Unit,
    private val limits: ReadLimits,
    private val observation: IntellijReadObservation,
) {
    fun prepare(subject: PsiNamedElement): RelationInventoryPreparation {
        observation.phase(referenceInventoryPhase(scope.request.meaning))
        val inventory = IntellijRelationInventory<RelationProviderLocator.Reference>(collector, limits, observation)
        val exhausted =
            ReferencesSearch.search(subject, scope.nativeScope, false)
                .forEach(
                    Processor { reference ->
                        cancellationCheck()
                        when (scope.admitProviderSite(reference.element.containingFile?.virtualFile)) {
                            RelationProviderScopeAdmission.ADMITTED ->
                                collector.admitProviderCandidate() ==
                                    IntellijRelationProviderEnumerationAdmission.READY &&
                                    inventory.append(locators.reference(reference))
                            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
                            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED -> {
                                observation.count(
                                    io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter.SCOPE_FILTERED
                                )
                                true
                            }
                            RelationProviderScopeAdmission.UNAVAILABLE ->
                                collector.blockPartition(
                                    io.github.amichne.kast.relation.contract.RelationLimitation.PROVIDER_INCOMPLETE
                                )
                        }
                    }
                )
        return inventory.finish(exhausted, RelationProviderState::references)
    }
}

internal fun referenceInventoryPhase(meaning: RelationMeaning): IntellijReadPhase =
    when (meaning) {
        RelationMeaning.References -> IntellijReadPhase.REFERENCE_INVENTORY
        RelationMeaning.Callers -> IntellijReadPhase.CALLER_REFERENCE_INVENTORY
        RelationMeaning.TypeUses -> IntellijReadPhase.TYPE_USE_REFERENCE_INVENTORY
        else -> error("Only reference-backed relation plans enter reference inventory")
    }
