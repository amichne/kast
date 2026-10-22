package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.call

/** Named discovery drains the existing detached partition supply before ranked semantic projection. */
internal fun collectDirectoryKotlinDeclarations(
    project: Project,
    plan: IntellijDirectoryDiscoveryPlan,
    limits: ReadLimits,
    allowance: IntellijDeclarationDiscoveryAllowance,
    callbacks: ScopedDeclarationCallbacks,
    partitions: IntellijDeclarationPartitionFiles,
    includeSource: (com.intellij.openapi.vfs.VirtualFile) -> Boolean = { true },
    retainedBytes: Long = 0L,
): Boolean {
    val prepared =
        when (val result = plan.prepareDirectoryCollection(limits, callbacks, retainedBytes)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return callbacks.reject(result.failure)
        }
    val adapter =
        IntellijDeclarationSourceAdapter(
            project,
            prepared,
            limits,
            allowance,
            callbacks.observation,
            callbacks.consume,
            partitions,
            includeSource,
        )
    val inventory = adapter.initialPartitions()
    val initial =
        when (inventory) {
            is IntellijDeclarationInitialInventory.Complete -> inventory.partitions
            is IntellijDeclarationInitialInventory.Rejected -> return callbacks.reject(inventory.cause)
            IntellijDeclarationInitialInventory.TimeLimit -> return callbacks.rejectTime()
        }
    val collection =
        NamedDirectoryPartitionCollection(
            initial,
            prepared.chargedBytes + retainedBytes,
            limits,
            callbacks,
            plan.request.budget.resources,
        )
    if (!collection.drain(adapter)) return false
    val visitor =
        ScopedKotlinDeclarationVisitor(
            PsiManager.getInstance(project),
            plan.request.constraints,
            plan.request.requestedDeclarationKinds(),
            { callbacks.observe() && callbacks.consume() },
            callbacks.qualify,
            callbacks.accept,
            observation = callbacks.observation,
        )
    return collection.files.values.all { visitor.readSource(it, plan.request.usesExactClasslikeAstTraversal()) }
}

private fun IntellijDirectoryDiscoveryPlan.prepareDirectoryCollection(
    limits: ReadLimits,
    callbacks: ScopedDeclarationCallbacks,
    retainedBytes: Long,
): Refinement<IntellijDirectoryDiscoveryPlan.Prepared, SymbolDiscoveryBlockCause> =
    callbacks.observation.call(IntellijReadCall.DISCOVERY_DIRECTORY_PLAN) {
        IntellijDirectoryDiscoveryPlan.Prepared.prepare(
            selection = this,
            consume = callbacks.consume,
            observation = callbacks.observation,
            limits = limits,
            retainedBytes = retainedBytes,
        )
    }

private class NamedDirectoryPartitionCollection(
    initial: List<SemanticFilePartition>,
    private val planBytes: Long,
    private val limits: ReadLimits,
    private val callbacks: ScopedDeclarationCallbacks,
    private val resources: io.github.amichne.kast.kernel.ResourceBudget,
) {
    private val frontier =
        java.util.TreeMap<String, SemanticFilePartition>().apply {
            initial.forEach { put(it.orderingPath, it) }
        }
    val files = java.util.TreeMap<String, org.jetbrains.kotlin.psi.KtFile>()
    private var retainedBytes = initial.sumOf { it.detachedBytes() }

    init {
        callbacks.observation.count(IntellijReadCounter.DISCOVERY_RETAINED_PARTITIONS, amount = initial.size)
        callbacks.observation.count(IntellijReadCounter.DISCOVERY_BYTES_CHARGED, amount = retainedBytes.toInt())
    }

    fun drain(adapter: IntellijDeclarationSourceAdapter): Boolean {
        if (!fits()) return false
        while (frontier.isNotEmpty()) {
            if (!callbacks.observe() || !callbacks.consume()) return false
            val partition = frontier.firstEntry().value
            val observed =
                callbacks.observation.call(IntellijReadCall.DISCOVERY_PARTITION_SUPPLY) { adapter.observe(partition) }
            if (!advance(partition, observed) || !fits()) return false
        }
        return true
    }

    private fun advance(partition: SemanticFilePartition, observed: IntellijDeclarationPartitionObservation): Boolean =
        when (observed) {
            is IntellijDeclarationPartitionObservation.Rejected -> callbacks.reject(observed.cause)
            IntellijDeclarationPartitionObservation.TimeLimit -> callbacks.rejectTime()
            IntellijDeclarationPartitionObservation.OutsideUniverse -> {
                frontier.pollFirstEntry()
                retainedBytes -= partition.detachedBytes()
                true
            }
            is IntellijDeclarationPartitionObservation.Source -> {
                frontier.pollFirstEntry()
                files[partition.orderingPath] = observed.file
                retainedBytes += NATIVE_FILE_BINDING_BYTES
                callbacks.observation.count(
                    IntellijReadCounter.DISCOVERY_BYTES_CHARGED,
                    amount = NATIVE_FILE_BINDING_BYTES.toInt(),
                )
                callbacks.observation.count(IntellijReadCounter.DISCOVERY_FILE_CANDIDATES)
                true
            }
            is IntellijDeclarationPartitionObservation.Directory -> expand(partition, observed.children)
        }

    private fun expand(partition: SemanticFilePartition, children: List<SemanticFilePartition>): Boolean {
        frontier.pollFirstEntry()
        retainedBytes -= partition.detachedBytes()
        for (child in children) {
            if (child.orderingPath in frontier || child.orderingPath in files)
                return callbacks.reject(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE)
            frontier[child.orderingPath] = child
            retainedBytes += child.detachedBytes()
            callbacks.observation.count(IntellijReadCounter.DISCOVERY_RETAINED_PARTITIONS)
            callbacks.observation.count(
                IntellijReadCounter.DISCOVERY_BYTES_CHARGED,
                amount = child.detachedBytes().toInt(),
            )
        }
        return true
    }

    private fun fits(): Boolean {
        val count = frontier.size + files.size
        val bytes = retainedBytes + planBytes + DIRECTORY_BINDING_BYTES
        if (
            resources is io.github.amichne.kast.kernel.ResourceBudget.Bounded &&
                count > limits[ReadLimitParameter.DISCOVERY_FILES].value
        )
            return callbacks.reject(SymbolDiscoveryBlockCause.PARTITION_CAPACITY)
        if (
            resources is io.github.amichne.kast.kernel.ResourceBudget.Bounded &&
                bytes > limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value
        )
            return callbacks.reject(SymbolDiscoveryBlockCause.RETENTION_LIMIT)
        callbacks.observation.count(IntellijReadCounter.DISCOVERY_RETENTION_PARTITION_SAMPLES, amount = count)
        callbacks.observation.count(IntellijReadCounter.DISCOVERY_RETENTION_BYTE_SAMPLES, amount = bytes.toInt())
        return true
    }
}

private fun ScopedDeclarationCallbacks.reject(cause: SymbolDiscoveryBlockCause): Boolean {
    qualify(
        when (cause) {
            SymbolDiscoveryBlockCause.PARTITION_CAPACITY,
            SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT -> SymbolDiscoveryQualification.WORK_LIMIT_REACHED
            SymbolDiscoveryBlockCause.RETENTION_LIMIT,
            SymbolDiscoveryBlockCause.ITEM_BYTE_LIMIT -> SymbolDiscoveryQualification.BYTE_LIMIT_REACHED
            SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE,
            SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE,
            SymbolDiscoveryBlockCause.QUALIFIED_PROVIDER,
            SymbolDiscoveryBlockCause.INPUT_REVISION_EXHAUSTED -> SymbolDiscoveryQualification.PROVIDER_FAILURE
        }
    )
    block(cause)
    return false
}

private fun ScopedDeclarationCallbacks.rejectTime(): Boolean {
    qualify(SymbolDiscoveryQualification.TIME_LIMIT_REACHED)
    return false
}

private const val DIRECTORY_BINDING_BYTES = 768L
private const val NATIVE_FILE_BINDING_BYTES = 768L
