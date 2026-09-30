package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase

/** Original cross-language definitions provider, normalized once and detached before semantic bounds. */
internal class IntellijDefinitionInventory(
    private val scope: CompiledRelationScope,
    private val locators: IntellijRelationLocators,
    private val collector: IntellijRelationCollector,
    private val cancellationCheck: () -> Unit,
    private val limits: ReadLimits,
    private val observation: IntellijReadObservation,
) {
    fun prepare(subject: PsiNamedElement): RelationInventoryPreparation {
        observation.phase(IntellijReadPhase.DEFINITION_INVENTORY)
        val inventory = IntellijRelationInventory<RelationProviderLocator.Definition>(collector, limits, observation)
        val exhausted =
            DefinitionsScopedSearch.search(subject, scope.nativeScope, false)
                .forEach(
                    Processor { provider ->
                        cancellationCheck()
                        if (collector.admitProviderCandidate() != IntellijRelationProviderEnumerationAdmission.READY)
                            false
                        else inventory.append(locators.definition(provider))
                    }
                )
        return inventory.finish(exhausted, RelationProviderState::definitions)
    }
}
