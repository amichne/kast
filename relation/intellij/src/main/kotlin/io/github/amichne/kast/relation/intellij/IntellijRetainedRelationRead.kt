package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderPosition
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase

/** One ordinal consumption protocol for all original native relation inventories. */
internal class IntellijRetainedRelationRead(
    project: Project,
    private val scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    private val limits: ReadLimits,
    private val observation: IntellijReadObservation,
    private val cancellationCheck: () -> Unit,
) {
    private val locators = IntellijRelationLocators(project, scope, projection, cancellationCheck, observation)

    fun references(
        request: RelationRequest,
        subject: PsiNamedElement,
        collector: IntellijRelationCollector,
        process: (PsiReference) -> Boolean,
        termination: (ProviderTermination) -> IntellijRelationTermination,
    ): IntellijRelationTermination =
        termination(
            readRelationInventory(
                request,
                collector,
                {
                    IntellijReferenceInventory(scope, locators, collector, cancellationCheck, limits, observation)
                        .prepare(subject)
                },
                { state, locator ->
                    observation.phase(IntellijReadPhase.REFERENCE_CONFIRMATION)
                    when (locator) {
                        is RelationProviderLocator.Reference ->
                            if (state.alreadyConfirmed(locator)) collector.dismissProviderItem()
                            else
                                confirm(request, state, locators.restoreReference(locator), locator, collector, process)
                        else -> collector.blockPartition(RelationLimitation.PROVIDER_INCOMPLETE)
                    }
                },
                cancellationCheck,
                observation,
            )
        )

    fun definitions(
        request: RelationRequest,
        subject: PsiNamedElement,
        collector: IntellijRelationCollector,
        process: (IntellijRestoredDefinition) -> Boolean,
        termination: (ProviderTermination) -> IntellijRelationTermination,
    ): IntellijRelationTermination =
        termination(
            readRelationInventory(
                request,
                collector,
                {
                    IntellijDefinitionInventory(scope, locators, collector, cancellationCheck, limits, observation)
                        .prepare(subject)
                },
                { state, locator ->
                    observation.phase(IntellijReadPhase.DEFINITION_CONFIRMATION)
                    when (locator) {
                        is RelationProviderLocator.Definition ->
                            confirm(request, state, locators.restoreDefinition(locator), locator, collector, process)
                        else -> collector.blockPartition(RelationLimitation.PROVIDER_INCOMPLETE)
                    }
                },
                cancellationCheck,
                observation,
            )
        )

    fun callees(
        request: RelationRequest,
        subject: PsiNamedElement,
        collector: IntellijRelationCollector,
        process: (CalleeProviderItem) -> Boolean,
        termination: (ProviderTermination) -> IntellijRelationTermination,
    ): IntellijRelationTermination =
        termination(
            readRelationInventory(
                request,
                collector,
                {
                    IntellijCalleeInventory(locators, collector, cancellationCheck, limits, observation)
                        .prepare(subject)
                },
                { state, locator ->
                    observation.phase(IntellijReadPhase.CALLEE_CONFIRMATION)
                    when (locator) {
                        is RelationProviderLocator.Callee ->
                            confirm(request, state, locators.restoreCallee(locator), locator, collector, process)
                        else -> collector.blockPartition(RelationLimitation.PROVIDER_INCOMPLETE)
                    }
                },
                cancellationCheck,
                observation,
            )
        )

    private fun <Value> confirm(
        request: RelationRequest,
        state: RelationProviderState,
        restored: Refinement<Value, RelationLimitation>,
        locator: RelationProviderLocator,
        collector: IntellijRelationCollector,
        process: (Value) -> Boolean,
    ): Boolean =
        when (restored) {
            is Refinement.Refined -> {
                observeRelationLocatorRestoration(request, state.consumedLocatorCount, observation)
                process(restored.value)
            }
            is Refinement.Rejected ->
                when (
                    val occurrence =
                        RelationOccurrence.fromBoundary(
                            locator.file,
                            locator.range.startInclusive,
                            locator.range.endExclusive,
                        )
                ) {
                    is Refinement.Refined ->
                        collector.examineIncomplete(restored.failure, RelationOmissionSample.Located(occurrence.value))
                    is Refinement.Rejected -> collector.blockPartition(RelationLimitation.PROVIDER_INCOMPLETE)
                }
        }
}

/** Native preparation and confirmation are explicit effects; successors consume only retained unfinished locators. */
internal fun readRelationInventory(
    request: RelationRequest,
    collector: IntellijRelationCollector,
    prepare: () -> RelationInventoryPreparation,
    confirm: (RelationProviderState, RelationProviderLocator) -> Boolean,
    cancellationCheck: () -> Unit,
    observation: IntellijReadObservation = IntellijReadObservation.None,
): ProviderTermination {
    var retained =
        when (val inventory = request.inventoryForInvocation(collector, prepare)) {
            is RelationInventoryPreparation.Prepared -> inventory.state
            RelationInventoryPreparation.Unavailable -> return ProviderTermination.HALTED
        }
    if (retained.provider == RelationProviderKind.PUBLISHED_TOPOLOGY_V1) {
        collector.blockPartition(RelationLimitation.UNSUPPORTED_ITEM)
        return ProviderTermination.HALTED
    }
    observation.count(IntellijReadCounter.RELATION_REPLAYED_PREFIX, amount = 0)
    while (retained.hasUnfinishedWork) {
        cancellationCheck()
        val locator = retained.prepared.first()
        if (collector.beginProviderItem(locator.descriptor) != IntellijRelationProviderItemAdmission.READY)
            return ProviderTermination.HALTED
        val continued = confirm(retained, locator)
        if (collector.providerItemConsumed) {
            retained = retained.consume(collector.providerConsumption)
            if (!collector.retainProviderState(retained)) return ProviderTermination.HALTED
        }
        if (!continued) return ProviderTermination.HALTED
    }
    return ProviderTermination.TERMINAL
}

private fun RelationRequest.inventoryForInvocation(
    collector: IntellijRelationCollector,
    prepare: () -> RelationInventoryPreparation,
): RelationInventoryPreparation {
    if (collector.admitProviderEnumeration() != IntellijRelationProviderEnumerationAdmission.READY)
        return RelationInventoryPreparation.Unavailable
    return when (val selected = position) {
        RelationReadPosition.Start -> prepare()
        is RelationReadPosition.Resume -> RelationInventoryPreparation.Prepared(selected.continuation.providerState)
    }
}

/** Actual restored locator ordinals only; this cannot observe work performed inside native provider APIs. */
internal fun observeRelationLocatorRestoration(
    request: RelationRequest,
    restoredOrdinal: RelationProviderPosition,
    observation: IntellijReadObservation,
) {
    val admitted =
        when (val position = request.position) {
            RelationReadPosition.Start -> RelationProviderPosition.Zero
            is RelationReadPosition.Resume -> position.continuation.providerState.consumedLocatorCount
        }
    if (restoredOrdinal.value < admitted.value) observation.count(IntellijReadCounter.RELATION_REPLAYED_PREFIX)
}
