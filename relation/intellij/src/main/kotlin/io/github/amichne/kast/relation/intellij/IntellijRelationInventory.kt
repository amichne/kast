package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

internal sealed interface RelationInventoryPreparation {
    data class Prepared(val state: RelationProviderState) : RelationInventoryPreparation

    data object Unavailable : RelationInventoryPreparation
}

/** The same finite detached inventory accounting for every native relation provider. */
internal class IntellijRelationInventory<Locator : RelationProviderLocator>(
    private val collector: IntellijRelationCollector,
    private val limits: ReadLimits,
    private val observation: IntellijReadObservation,
) {
    private val locators = mutableListOf<Locator>()
    private var retainedBytes = INVENTORY_STRUCTURE_BYTES
    private var requiredBytes = retainedBytes

    fun append(locator: Refinement<Locator, RelationLimitation>): Boolean {
        val value =
            when (locator) {
                is Refinement.Refined -> locator.value
                is Refinement.Rejected -> return collector.blockPartition(locator.failure)
            }
        requiredBytes = retainedBytes + value.retainedBytes
        if (requiredBytes > limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value)
            return collector.blockPartition(RelationLimitation.RETENTION_LIMIT_REACHED)
        retainedBytes = requiredBytes
        locators += value
        return true
    }

    fun finish(exhausted: Boolean, prepare: (List<Locator>) -> RelationProviderState): RelationInventoryPreparation {
        if (!exhausted || collector.admitProviderEnumeration() != IntellijRelationProviderEnumerationAdmission.READY) {
            observeRequiredBytes(requiredBytes)
            collector.blockPartition(RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE)
            return RelationInventoryPreparation.Unavailable
        }
        val state = prepare(locators)
        observeRequiredBytes(state.retainedBytes)
        observation.count(IntellijReadCounter.RELATION_PARTITIONS_PREPARED)
        return if (collector.retainProviderState(state, preparedPartition = true))
            RelationInventoryPreparation.Prepared(state)
        else RelationInventoryPreparation.Unavailable
    }

    private fun observeRequiredBytes(value: Long) =
        when (val measured = IntellijReadGaugeValue.parse(value)) {
            is Refinement.Refined ->
                observation.measure(IntellijReadGauge.RELATION_INVENTORY_RETAINED_BYTES, measured.value)
            is Refinement.Rejected -> error("Detached inventory accounting cannot produce a negative estimate")
        }
}

private const val INVENTORY_STRUCTURE_BYTES = 512L
