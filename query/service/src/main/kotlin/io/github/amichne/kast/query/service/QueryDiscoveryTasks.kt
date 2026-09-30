package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation

internal sealed interface QueryDiscoveryTaskTransition {
    data object NotStarted : QueryDiscoveryTaskTransition

    data object Advanced : QueryDiscoveryTaskTransition

    data object ContractRejected : QueryDiscoveryTaskTransition

    data class Rejected(val result: QueryExecutionResult.Rejected) : QueryDiscoveryTaskTransition
}

/** Retains discovery producer order and merges sequential coverage without erasing retained evidence. */
internal class QueryDiscoveryTasks(
    private val state: QueryExecutionState,
    private val tasks: ArrayDeque<PipelineTask>,
) {
    val discoveryObservations = mutableListOf<io.github.amichne.kast.query.contract.QueryDiscoveryObservation>()
    private val sequentialDiscoveryIndices = mutableSetOf<Int>()

    fun discovered(
        result: DiscoveryExecution,
        next: ExactQueryStage,
        producer: PipelineTask.Discover? = null,
    ): QueryDiscoveryTaskTransition =
        when (result) {
            DiscoveryExecution.NotStarted -> QueryDiscoveryTaskTransition.NotStarted
            is DiscoveryExecution.Rejected -> QueryDiscoveryTaskTransition.Rejected(result.result)
            is DiscoveryExecution.Discovered -> {
                tasks.removeFirst()
                when (val progress = result.progress) {
                    io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress.Exhausted -> Unit
                    is io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress.Blocked ->
                        state.limit(QueryLimitation.DISCOVERY_INCOMPLETE)
                    is io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress.Resumable -> {
                        if (
                            producer == null ||
                                producer.remainder != null && !progress.remainder.advancesFrom(producer.remainder)
                        ) {
                            state.contractViolation = true
                            return QueryDiscoveryTaskTransition.ContractRejected
                        }
                        tasks.addFirst(producer.copy(remainder = progress.remainder))
                    }
                }
                result.values.asReversed().forEach { tasks.addFirst(PipelineTask.Candidate(it, next)) }
                result.observation?.let {
                    tasks.addFirst(
                        PipelineTask.DiscoveryObservation(
                            it,
                            DiscoveryObservationOrigin.SEQUENTIAL_EXECUTION,
                        )
                    )
                }
                QueryDiscoveryTaskTransition.Advanced
            }
        }

    fun observation(task: PipelineTask.DiscoveryObservation, emit: (Long, () -> Unit) -> Boolean): Boolean {
        if (task.origin == DiscoveryObservationOrigin.RETAINED_EVIDENCE && task.value in discoveryObservations) {
            tasks.removeFirst()
            return true
        }
        val existing =
            if (task.origin == DiscoveryObservationOrigin.SEQUENTIAL_EXECUTION)
                sequentialDiscoveryIndices.firstOrNull { discoveryObservations[it].sameProducer(task.value) }
            else null
        val merged = existing?.let { discoveryObservations[it].followedBy(task.value) } ?: task.value
        val previousBytes = existing?.let { discoveryObservations[it].projectedUtf8Size() } ?: 0L
        val addedBytes = (merged.projectedUtf8Size() - previousBytes).coerceAtLeast(0L)
        return emit(addedBytes) {
            if (existing == null) {
                if (task.origin == DiscoveryObservationOrigin.SEQUENTIAL_EXECUTION)
                    sequentialDiscoveryIndices += discoveryObservations.size
                discoveryObservations += merged
            } else discoveryObservations[existing] = merged
        }
    }
}
