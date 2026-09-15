package io.github.amichne.kast.protocol.contract

/** Admission proof accompanies rejection without changing the finite semantic reason. */
sealed interface SourceReadFailure : OperationRejection

data class AdmittedSourceReadRejection(val reason: SourceReadCause, val executionBudget: ExecutionBudgetReport) :
    SourceReadFailure

sealed interface RelationReadFailure : OperationRejection

data class AdmittedRelationReadRejection(
    val reason: RelationReadRejection,
    val executionBudget: ExecutionBudgetReport,
) : RelationReadFailure

sealed interface TraversalRunFailure : OperationRejection

data class AdmittedTraversalRunRejection(
    val reason: TraversalRunRejection,
    val executionBudget: ExecutionBudgetReport,
) : TraversalRunFailure

sealed interface QueryRunFailure : OperationRejection

data class AdmittedQueryRunRejection(val reason: QueryRunRejection, val executionBudget: ExecutionBudgetReport) :
    QueryRunFailure

fun SourceReadFailure.reason(): SourceReadCause =
    when (this) {
        is SourceReadCause -> this
        is AdmittedSourceReadRejection -> reason
    }

fun RelationReadFailure.reason(): RelationReadRejection =
    when (this) {
        is RelationReadRejection -> this
        is AdmittedRelationReadRejection -> reason
    }

fun TraversalRunFailure.reason(): TraversalRunRejection =
    when (this) {
        is TraversalRunRejection -> this
        is AdmittedTraversalRunRejection -> reason
    }

fun QueryRunFailure.reason(): QueryRunRejection =
    when (this) {
        is QueryRunRejection -> this
        is AdmittedQueryRunRejection -> reason
    }

fun SourceReadFailure.budgetPresence(): ExecutionBudgetPresence =
    when (this) {
        is SourceReadCause -> ExecutionBudgetPresence.Absent
        is AdmittedSourceReadRejection -> ExecutionBudgetPresence.Present(executionBudget)
    }

fun RelationReadFailure.budgetPresence(): ExecutionBudgetPresence =
    when (this) {
        is RelationReadRejection -> ExecutionBudgetPresence.Absent
        is AdmittedRelationReadRejection -> ExecutionBudgetPresence.Present(executionBudget)
    }

fun TraversalRunFailure.budgetPresence(): ExecutionBudgetPresence =
    when (this) {
        is TraversalRunRejection -> ExecutionBudgetPresence.Absent
        is AdmittedTraversalRunRejection -> ExecutionBudgetPresence.Present(executionBudget)
    }

fun QueryRunFailure.budgetPresence(): ExecutionBudgetPresence =
    when (this) {
        is QueryRunRejection -> ExecutionBudgetPresence.Absent
        is AdmittedQueryRunRejection -> ExecutionBudgetPresence.Present(executionBudget)
    }
