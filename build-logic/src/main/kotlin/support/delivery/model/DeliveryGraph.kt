package support.delivery

import java.util.PriorityQueue

enum class DeliveryGraphFailure : DeliveryFailure {
    EMPTY_DEPENDENCY,
    SELECTED_LANE_NOT_MEMBER,
    MISSING_LIFECYCLE_TARGET,
    EMPTY_GRAPH,
    DUPLICATE_TASK,
    UNKNOWN_TERMINAL,
    UNKNOWN_DEPENDENCY,
    SELF_DEPENDENCY,
    CYCLE,
    UNKNOWN_LIFECYCLE_TASK,
    RECOVERY_DEAD_END,
}

internal class NonEmptyTaskIds private constructor(val values: Set<TaskId>) {
    companion object {
        /**
         * Proof transition: `Set<TaskId> -> NonEmptyTaskIdsResult`.
         * Establishes at least one task identity. Expected failure is closed in
         * [NonEmptyTaskIdsResult]; raw sets remain inside this graph-model boundary.
         */
        fun refine(values: Set<TaskId>): NonEmptyTaskIdsResult =
            if (values.isEmpty()) {
                NonEmptyTaskIdsResult.Rejected
            } else {
                NonEmptyTaskIdsResult.Complete(NonEmptyTaskIds(values.toSet()))
            }
    }
}

internal sealed interface NonEmptyTaskIdsResult {
    data class Complete(val taskIds: NonEmptyTaskIds) : NonEmptyTaskIdsResult
    data object Rejected : NonEmptyTaskIdsResult
}

sealed interface DeliveryDependency {
    data object Root : DeliveryDependency
    class AllOf internal constructor(internal val taskIds: NonEmptyTaskIds) : DeliveryDependency
    class OneOf internal constructor(internal val taskIds: NonEmptyTaskIds) : DeliveryDependency
    class SelectedLaneJoin internal constructor(
        internal val candidates: NonEmptyTaskIds,
        val selected: TaskId,
    ) : DeliveryDependency
}

sealed interface DeliveryDependencyResult {
    data class Complete(val dependency: DeliveryDependency) : DeliveryDependencyResult
    data class Rejected(val failure: DeliveryGraphFailure) : DeliveryDependencyResult
}

/**
 * Proof transition: `Set<TaskId> -> DeliveryDependencyResult`.
 * Establishes a nonempty all-of dependency. Expected failure is [DeliveryGraphFailure]; raw sets
 * may be extracted only by graph validation and projection boundaries.
 */
fun refineAllOf(taskIds: Set<TaskId>): DeliveryDependencyResult =
    refineDependency(taskIds) { DeliveryDependency.AllOf(it) }

/**
 * Proof transition: `Set<TaskId> -> DeliveryDependencyResult`.
 * Establishes a nonempty one-of dependency without flattening alternatives. Expected failure is
 * [DeliveryGraphFailure]; raw sets may be extracted only by graph validation and projections.
 */
fun refineOneOf(taskIds: Set<TaskId>): DeliveryDependencyResult =
    refineDependency(taskIds) { DeliveryDependency.OneOf(it) }

/**
 * Proof transition: candidate `Set<TaskId>` plus selected `TaskId -> DeliveryDependencyResult`.
 * Establishes nonempty candidates and selected-lane membership. Expected failure is
 * [DeliveryGraphFailure]; raw sets may be extracted only by graph validation and projections.
 */
fun refineSelectedLaneJoin(
    candidates: Set<TaskId>,
    selected: TaskId,
): DeliveryDependencyResult = when (val refined = NonEmptyTaskIds.refine(candidates)) {
    is NonEmptyTaskIdsResult.Complete -> {
        if (selected in refined.taskIds.values) {
            DeliveryDependencyResult.Complete(
                DeliveryDependency.SelectedLaneJoin(refined.taskIds, selected),
            )
        } else {
            DeliveryDependencyResult.Rejected(DeliveryGraphFailure.SELECTED_LANE_NOT_MEMBER)
        }
    }
    NonEmptyTaskIdsResult.Rejected ->
        DeliveryDependencyResult.Rejected(DeliveryGraphFailure.EMPTY_DEPENDENCY)
}

private fun refineDependency(
    taskIds: Set<TaskId>,
    complete: (NonEmptyTaskIds) -> DeliveryDependency,
): DeliveryDependencyResult = when (val refined = NonEmptyTaskIds.refine(taskIds)) {
    is NonEmptyTaskIdsResult.Complete ->
        DeliveryDependencyResult.Complete(complete(refined.taskIds))
    NonEmptyTaskIdsResult.Rejected ->
        DeliveryDependencyResult.Rejected(DeliveryGraphFailure.EMPTY_DEPENDENCY)
}

/** Proof transition: authored `String -> LifecycleTarget`; establishes a bound lifecycle identity; expected raw failure uses [refineLifecycleTarget]; raw text exits only at projections. */
@JvmInline
value class LifecycleTarget internal constructor(val value: String) {
    init { require(value.matches(Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*"))) }
}

sealed interface LifecycleTargetResult {
    data class Complete(val target: LifecycleTarget) : LifecycleTargetResult
    data class Rejected(val failure: DeliveryGraphFailure) : LifecycleTargetResult
}

/**
 * Proof transition: `String -> LifecycleTargetResult`.
 * Establishes a nonempty canonical retirement target. Expected failure is [DeliveryGraphFailure];
 * raw text may be extracted only at graph projection boundaries.
 */
fun refineLifecycleTarget(value: String): LifecycleTargetResult =
    if (value.matches(Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*"))) {
        LifecycleTargetResult.Complete(LifecycleTarget(value))
    } else {
        LifecycleTargetResult.Rejected(DeliveryGraphFailure.MISSING_LIFECYCLE_TARGET)
    }

sealed interface LifecycleEdge {
    data class Retirement(val trigger: TaskId, val target: LifecycleTarget) : LifecycleEdge
    data class Invalidation(val trigger: TaskId, val invalidates: TaskId) : LifecycleEdge
    data class Recovery(
        val failed: TaskId,
        val recovery: TaskId,
        val resumesAt: TaskId,
    ) : LifecycleEdge
}

data class GraphTask(val id: TaskId, val dependency: DeliveryDependency)

@ConsistentCopyVisibility
data class TypedGraph internal constructor(
    val tasks: Map<TaskId, GraphTask>,
    val order: List<TaskId>,
    val waves: Map<TaskId, Int>,
    val lifecycleEdges: List<LifecycleEdge>,
)

sealed interface TypedGraphResult {
    data class Complete(val graph: TypedGraph) : TypedGraphResult
    data class Rejected(val failure: DeliveryGraphFailure) : TypedGraphResult
}

/**
 * Proof transition: graph task/lifecycle declarations plus terminal `TaskId -> TypedGraphResult`.
 * Establishes unique closed references, acyclic potential ordering, derived waves, bound lifecycle
 * tasks, and recovery reachability. Expected failure is [DeliveryGraphFailure]; raw collections may
 * be extracted only by this model owner and projection boundaries.
 */
fun refineTypedGraph(
    tasks: List<GraphTask>,
    lifecycleEdges: List<LifecycleEdge>,
    terminal: TaskId,
): TypedGraphResult {
    if (tasks.isEmpty()) return TypedGraphResult.Rejected(DeliveryGraphFailure.EMPTY_GRAPH)
    val byId = tasks.associateBy { it.id }
    if (byId.size != tasks.size) return TypedGraphResult.Rejected(DeliveryGraphFailure.DUPLICATE_TASK)
    if (terminal !in byId) return TypedGraphResult.Rejected(DeliveryGraphFailure.UNKNOWN_TERMINAL)
    tasks.forEach { task ->
        val predecessors = task.dependency.potentialPredecessors()
        if (task.id in predecessors) return TypedGraphResult.Rejected(DeliveryGraphFailure.SELF_DEPENDENCY)
        if (predecessors.any { it !in byId }) {
            return TypedGraphResult.Rejected(DeliveryGraphFailure.UNKNOWN_DEPENDENCY)
        }
    }
    val ordering = when (val result = deriveOrdering(byId)) {
        is OrderingResult.Complete -> result.ordering
        OrderingResult.Rejected -> return TypedGraphResult.Rejected(DeliveryGraphFailure.CYCLE)
    }
    when (val lifecycle = validateLifecycle(lifecycleEdges, byId.keys, terminal, ordering.outgoing)) {
        LifecycleValidation.Complete -> Unit
        is LifecycleValidation.Rejected -> return TypedGraphResult.Rejected(lifecycle.failure)
    }
    return TypedGraphResult.Complete(TypedGraph(
        byId, ordering.order, deriveWaves(ordering.order, byId), lifecycleEdges.toList(),
    ))
}

private data class DerivedOrdering(
    val order: List<TaskId>,
    val outgoing: Map<TaskId, Set<TaskId>>,
)

private sealed interface OrderingResult {
    data class Complete(val ordering: DerivedOrdering) : OrderingResult
    data object Rejected : OrderingResult
}

private fun deriveOrdering(byId: Map<TaskId, GraphTask>): OrderingResult {
    val indegree = byId.keys.associateWith { 0 }.toMutableMap()
    val outgoing = byId.keys.associateWith { linkedSetOf<TaskId>() }.toMutableMap()
    byId.values.forEach { task ->
        task.dependency.potentialPredecessors().forEach { predecessor ->
            indegree[task.id] = indegree.getValue(task.id) + 1
            outgoing.getValue(predecessor) += task.id
        }
    }
    val ready = PriorityQueue<TaskId>().apply {
        addAll(indegree.filterValues { it == 0 }.keys)
    }
    val order = mutableListOf<TaskId>()
    while (ready.isNotEmpty()) {
        val current = ready.remove()
        order += current
        outgoing.getValue(current).sorted().forEach { next ->
            indegree[next] = indegree.getValue(next) - 1
            if (indegree.getValue(next) == 0) ready += next
        }
    }
    return if (order.size == byId.size) {
        OrderingResult.Complete(DerivedOrdering(order, outgoing.mapValues { it.value.toSet() }))
    } else {
        OrderingResult.Rejected
    }
}

private fun deriveWaves(order: List<TaskId>, byId: Map<TaskId, GraphTask>): Map<TaskId, Int> =
    buildMap {
        order.forEach { taskId ->
            val dependency = byId.getValue(taskId).dependency
            put(
                taskId,
                when (dependency) {
                    DeliveryDependency.Root -> 0
                    is DeliveryDependency.AllOf -> 1 + dependency.taskIds.values.maxOf(::getValue)
                    is DeliveryDependency.OneOf -> 1 + dependency.taskIds.values.minOf(::getValue)
                    is DeliveryDependency.SelectedLaneJoin -> 1 + getValue(dependency.selected)
                },
            )
        }
    }

private sealed interface LifecycleValidation {
    data object Complete : LifecycleValidation
    data class Rejected(val failure: DeliveryGraphFailure) : LifecycleValidation
}

private fun validateLifecycle(
    edges: List<LifecycleEdge>,
    taskIds: Set<TaskId>,
    terminal: TaskId,
    outgoing: Map<TaskId, Set<TaskId>>,
): LifecycleValidation {
    edges.forEach { edge ->
        val referenced = when (edge) {
            is LifecycleEdge.Retirement -> setOf(edge.trigger)
            is LifecycleEdge.Invalidation -> setOf(edge.trigger, edge.invalidates)
            is LifecycleEdge.Recovery -> setOf(edge.failed, edge.recovery, edge.resumesAt)
        }
        if (!taskIds.containsAll(referenced)) {
            return LifecycleValidation.Rejected(DeliveryGraphFailure.UNKNOWN_LIFECYCLE_TASK)
        }
        if (edge is LifecycleEdge.Recovery && !reaches(edge.resumesAt, terminal, outgoing)) {
            return LifecycleValidation.Rejected(DeliveryGraphFailure.RECOVERY_DEAD_END)
        }
    }
    return LifecycleValidation.Complete
}

private fun reaches(start: TaskId, target: TaskId, outgoing: Map<TaskId, Set<TaskId>>): Boolean {
    val pending = ArrayDeque<TaskId>().apply { add(start) }
    val seen = mutableSetOf<TaskId>()
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (current == target) return true
        if (seen.add(current)) pending.addAll(outgoing.getValue(current))
    }
    return false
}

private fun DeliveryDependency.potentialPredecessors(): Set<TaskId> = when (this) {
    DeliveryDependency.Root -> emptySet()
    is DeliveryDependency.AllOf -> taskIds.values
    is DeliveryDependency.OneOf -> taskIds.values
    is DeliveryDependency.SelectedLaneJoin -> setOf(selected)
}
