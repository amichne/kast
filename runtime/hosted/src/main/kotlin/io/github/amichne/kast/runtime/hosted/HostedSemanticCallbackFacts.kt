package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CallbackReadmission
import io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup
import io.github.amichne.kast.relation.contract.CallbackSummaryCachePort
import io.github.amichne.kast.relation.contract.CallbackSummaryCachePreparationPort
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.topology.build.SemanticCallbackFactStore
import io.github.amichne.kast.topology.build.SemanticCallbackLookup
import io.github.amichne.kast.topology.build.SemanticCallbackPublication
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticDependencyInventory
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.intellij.IntellijSemanticDependencyCapture
import io.github.amichne.kast.topology.intellij.SemanticDependencyCapture
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import java.nio.file.Path

/** Project lifetime facts are separate from strict epoch-bound references and continuations. */
@Service(Service.Level.PROJECT)
class HostedSemanticCallbackFacts : Disposable {
    private sealed interface State {
        data object Empty : State

        data class Active(val limits: ReadLimits, val store: SemanticCallbackFactStore) : State

        data object Retired : State
    }

    private var state: State = State.Empty

    @Synchronized
    internal fun preparation(
        project: Project,
        context: HostedSemanticReadContext,
    ): CallbackSummaryCachePreparationPort =
        when (val selected = selectStore(context.limits)) {
            is Refinement.Refined -> HostedCallbackFactPreparation(project, context, selected.value)
            is Refinement.Rejected -> CallbackSummaryCachePreparationPort.Disabled
        }

    internal enum class StoreSelectionFailure {
        RETIRED
    }

    @Synchronized
    internal fun selectStore(limits: ReadLimits): Refinement<SemanticCallbackFactStore, StoreSelectionFailure> {
        val current =
            when (val previous = state) {
                State.Retired -> return Refinement.Rejected(StoreSelectionFailure.RETIRED)
                State.Empty -> State.Active(limits, SemanticCallbackFactStore(limits)).also { state = it }
                is State.Active ->
                    if (ReadLimitParameter.entries.all { previous.limits[it] == limits[it] }) previous
                    else {
                        previous.store.retire()
                        State.Active(limits, SemanticCallbackFactStore(limits)).also { state = it }
                    }
            }
        return Refinement.Refined(current.store)
    }

    @Synchronized
    override fun dispose() {
        when (val current = state) {
            is State.Active -> current.store.retire()
            State.Empty,
            State.Retired -> Unit
        }
        state = State.Retired
    }
}

private class HostedCallbackFactPreparation(
    private val project: Project,
    private val context: HostedSemanticReadContext,
    private val store: SemanticCallbackFactStore,
) : CallbackSummaryCachePreparationPort {
    override fun prepare(
        request: RelationRequest,
        remaining: ResourceBudget,
        charge: (RelationWorkCount) -> Unit,
    ): CallbackSummaryCachePort {
        if (
            request.meaning != io.github.amichne.kast.relation.contract.RelationMeaning.Callers &&
                request.meaning != io.github.amichne.kast.relation.contract.RelationMeaning.Callees
        )
            return CallbackSummaryCachePort.Disabled
        // Root-filtered epochs do not prove unchanged external SDK/classpath inputs.
        // Capture and use therefore share the caller's exact native read action.
        val allowance =
            when (val admitted = optionalPreparationBudget(remaining)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return CallbackSummaryCachePort.Disabled
            }
        context.observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS)
        return when (
            val capture =
                IntellijSemanticDependencyCapture(context.limits, context.observation)
                    .capture(
                        project,
                        context.authority,
                        context.model,
                        context.model.sourceRoots.mapTo(linkedSetOf(), ModelOwnedSourceRoot::module),
                        allowance,
                        onCost = { charge(measuredWork(it.workUnits)) },
                    )
        ) {
            is SemanticDependencyCapture.Unavailable -> {
                context.observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                CallbackSummaryCachePort.Disabled
            }
            is SemanticDependencyCapture.Captured ->
                HostedCallbackFactCache(capture.snapshot, store, context.observation)
        }
    }
}

/** One complete query observation supplies smaller per-formal dependency partitions. */
internal class HostedCallbackFactCache(
    private val captured: SemanticDependencySnapshot,
    private val store: SemanticCallbackFactStore,
    private val observation: io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation,
) : CallbackSummaryCachePort {
    override val namedRelations: io.github.amichne.kast.relation.contract.NamedRelationCachePort =
        HostedNamedRelationFacts(store, observation) { request ->
            if (request.meaning == io.github.amichne.kast.relation.contract.RelationMeaning.Callers)
                HostedCallbackPartition.Available(captured)
            else partition(request.subject)
        }
    override val suppliers: io.github.amichne.kast.relation.contract.CallbackSupplierCachePort =
        HostedCallbackSupplierFacts(captured, store, observation)
    private val partitions = mutableMapOf<WorkspaceModuleIdentity, SemanticDependencySnapshot>()

    override fun find(
        formal: CallbackParameterIdentity,
        readmit: (CallbackParameterSummary) -> CallbackReadmission<CallbackParameterSummary>,
    ): CallbackSummaryCacheLookup {
        val snapshot =
            when (val admitted = partition(formal.callable)) {
                is HostedCallbackPartition.Available -> admitted.snapshot
                is HostedCallbackPartition.Rejected -> {
                    observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                    return CallbackSummaryCacheLookup.Miss
                }
            }
        return when (val cached = store.find(snapshot, formal)) {
            SemanticCallbackLookup.Missing -> CallbackSummaryCacheLookup.Miss
            is SemanticCallbackLookup.Current -> {
                CallbackSummaryCacheLookup.Found(cached.summary)
            }
            is SemanticCallbackLookup.Reusable -> restore(snapshot, formal, readmit(cached.previous))
            is SemanticCallbackLookup.Invalidated -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_INVALIDATED)
                observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                CallbackSummaryCacheLookup.Miss
            }
            is SemanticCallbackLookup.Rejected -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED)
                CallbackSummaryCacheLookup.Miss
            }
        }
    }

    private fun restore(
        snapshot: SemanticDependencySnapshot,
        formal: CallbackParameterIdentity,
        restored: CallbackReadmission<CallbackParameterSummary>,
    ): CallbackSummaryCacheLookup {
        val summary =
            when (restored) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> {
                    observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                    return CallbackSummaryCacheLookup.Miss
                }
            }
        if (summary.formal != formal) {
            observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
            return CallbackSummaryCacheLookup.Miss
        }
        return when (store.publish(snapshot, summary)) {
            SemanticCallbackPublication.Published -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_PUBLISHED)
                CallbackSummaryCacheLookup.Found(summary)
            }
            SemanticCallbackPublication.CapacityExceeded -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED)
                CallbackSummaryCacheLookup.Found(summary)
            }
            is SemanticCallbackPublication.Rejected -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED)
                CallbackSummaryCacheLookup.Miss
            }
        }
    }

    override fun admitted(summary: CallbackParameterSummary) {
        observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED)
    }

    override fun retain(summary: CallbackParameterSummary) {
        val snapshot =
            when (val admitted = partition(summary.formal.callable)) {
                is HostedCallbackPartition.Available -> admitted.snapshot
                is HostedCallbackPartition.Rejected -> {
                    observation.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED)
                    return
                }
            }
        observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_EXTRACTED)
        observation.count(
            when (store.publish(snapshot, summary)) {
                SemanticCallbackPublication.Published -> IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_PUBLISHED
                SemanticCallbackPublication.CapacityExceeded,
                is SemanticCallbackPublication.Rejected -> IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED
            }
        )
    }

    @Synchronized
    private fun partition(
        endpoint: io.github.amichne.kast.relation.contract.RelationEndpoint
    ): HostedCallbackPartition {
        val source =
            captured.inventory.files.singleOrNull {
                Path.of(captured.authority.workspaceRoot.value).resolve(it.path.value).toString() ==
                    endpoint.file.stableValue
            } ?: return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.SourceNotInventoried)
        partitions[source.root.module]?.let {
            return HostedCallbackPartition.Available(it)
        }
        val graph = captured.inventory.closure.graph
        val closure =
            when (val admitted = graph.closure(setOf(source.root.module))) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Dependency(admitted.failure))
            }
        val completed = mutableListOf<CompleteSemanticModuleSources>()
        for (module in closure.modules) when (
            val admitted =
                CompleteSemanticModuleSources.fromCompiler(
                    graph,
                    module,
                    captured.inventory.files.filter { it.root.module == module },
                )
        ) {
            is Refinement.Refined -> completed += admitted.value
            is Refinement.Rejected ->
                return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Inventory(admitted.failure))
        }
        val inventory =
            when (val admitted = SemanticDependencyInventory.admit(closure, completed)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Inventory(admitted.failure))
            }
        return when (
            val admitted = SemanticDependencySnapshot.fromCompiler(captured.authority, inventory, captured.inputs)
        ) {
            is Refinement.Refined ->
                HostedCallbackPartition.Available(admitted.value.also { partitions[source.root.module] = it })
            is Refinement.Rejected ->
                HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Snapshot(admitted.failure))
        }
    }
}

/** Optional preparation may consume at most one quarter of the current grant, leaving room for ordinary extraction. */
internal fun optionalPreparationBudget(
    budget: ResourceBudget
): Refinement<ResourceBudget, io.github.amichne.kast.kernel.PositiveLimitFailure> {
    val work =
        when (val parsed = WorkUnitLimit.parse(budget.workUnitLimit.value / 4)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    val time =
        when (val parsed = ElapsedTimeLimitMillis.parse(budget.elapsedTimeLimit.value / 4)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    return Refinement.Refined(budget.copy(workUnitLimit = work, elapsedTimeLimit = time))
}

private fun measuredWork(work: Long): RelationWorkCount =
    when (val count = RelationWorkCount.parse(work)) {
        is Refinement.Refined -> count.value
        is Refinement.Rejected -> error("Nonnegative measured preparation work lost its proof")
    }

internal sealed interface HostedCallbackPartition {
    data class Available(val snapshot: SemanticDependencySnapshot) : HostedCallbackPartition

    data class Rejected(val cause: HostedCallbackPartitionFailure) : HostedCallbackPartition
}

internal sealed interface HostedCallbackPartitionFailure {
    data object SourceNotInventoried : HostedCallbackPartitionFailure

    data class Dependency(val cause: io.github.amichne.kast.topology.contract.SemanticDependencyFailure) :
        HostedCallbackPartitionFailure

    data class Inventory(val cause: io.github.amichne.kast.topology.contract.SemanticInventoryFailure) :
        HostedCallbackPartitionFailure

    data class Snapshot(val cause: io.github.amichne.kast.topology.contract.SemanticSnapshotAdmissionFailure) :
        HostedCallbackPartitionFailure
}
