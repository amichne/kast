package kast.baseline.coordinator

import kast.baseline.model.*
import kast.baseline.read.ReadCoordinator

/** These ports are implemented by the existing owners when this example is integrated. */
fun interface RuntimeStart { fun startOrReuse(workspace: WorkspaceId): Outcome<WorkspaceId> }
fun interface IndexPreparation {
    fun ensureCurrent(workspace: WorkspaceId, budget: PreparationBudget): Outcome<Coordinates>
}
fun interface TopologyPreparation {
    fun buildOrReuse(coordinates: Coordinates, budget: PreparationBudget): Outcome<Coordinates>
}

/** Construction cannot be obtained through a runtime handle or the read-only interface. */
class ReadyWorkspace private constructor(val coordinates: Coordinates) {
    companion object {
        internal fun afterPublication(coordinates: Coordinates): ReadyWorkspace = ReadyWorkspace(coordinates)
    }
}

class StartCoordinator(private val runtime: RuntimeStart, private val index: IndexPreparation) {
    fun start(workspace: WorkspaceId, budget: PreparationBudget): Outcome<ReadyWorkspace> {
        when (val started = runtime.startOrReuse(workspace)) {
            is Outcome.Complete -> if (started.value != workspace)
                return Outcome.Rejected(Failure.WRONG_WORKSPACE)
            is Outcome.Qualified -> return Outcome.Rejected(Failure.PREPARATION_REJECTED)
            is Outcome.Rejected -> return started
        }
        return when (val published = index.ensureCurrent(workspace, budget)) {
            is Outcome.Complete -> if (published.value.workspace == workspace)
                Outcome.Complete(ReadyWorkspace.afterPublication(published.value))
            else Outcome.Rejected(Failure.WRONG_WORKSPACE)
            is Outcome.Qualified -> Outcome.Rejected(Failure.PREPARATION_REJECTED)
            is Outcome.Rejected -> published
        }
    }
}

/** The traversal request, not an arbitrary read, supplies the topology-preparation budget. */
class TraversalCoordinator(private val read: ReadCoordinator, private val topology: TopologyPreparation) {
    fun prepare(selector: Selector, budget: PreparationBudget): Outcome<Coordinates> {
        when (val current = read.read(selector)) {
            is Outcome.Complete -> Unit
            is Outcome.Qualified -> return Outcome.Rejected(Failure.PREPARATION_REJECTED)
            is Outcome.Rejected -> return current
        }
        return when (val published = topology.buildOrReuse(selector.coordinates, budget).atCoordinates(selector.coordinates)) {
            is Outcome.Complete -> published
            is Outcome.Qualified -> Outcome.Rejected(Failure.TOPOLOGY_REJECTED)
            is Outcome.Rejected -> published
        }
    }
}

