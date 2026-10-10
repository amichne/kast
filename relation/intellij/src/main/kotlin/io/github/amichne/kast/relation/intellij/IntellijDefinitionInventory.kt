package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
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
        val admission = NativeRelationScopeAdmission(observation) { collector.admitProviderCallback(cancellationCheck) }
        val exhausted =
            observation.forEachDefinition(subject, scope.nativeScope, admission) { provider ->
                if (
                    collector.admitProviderCallback(cancellationCheck) !=
                        IntellijRelationProviderEnumerationAdmission.READY
                )
                    return@forEachDefinition false
                when (scope.admitProviderSite(provider.containingFile?.virtualFile)) {
                    RelationProviderScopeAdmission.ADMITTED ->
                        collector.admitProviderCandidate() == IntellijRelationProviderEnumerationAdmission.READY &&
                            inventory.append(locators.definition(provider))
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
        return inventory.finish(exhausted, RelationProviderState::definitions)
    }
}
