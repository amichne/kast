package io.github.amichne.kast.protocol.contract

data class TraversalRunResult(
    /** Canonical workspace root shared by every selector and proof in [records]. */
    val snapshotRoot: ProtocolText,
    val records: BoundedProtocolList<TraversalRecordDocument>,
    val progress: TraversalProgressDocument = TraversalProgressDocument(),
    val strategy: TraversalStrategyDocument = TraversalStrategyDocument.BreadthFirst,
    val partialExpansions: BoundedProtocolList<TraversalPartialExpansionDocument> =
        TraversalPartialExpansionDocument.Empty,
    val executionBudget: ExecutionBudgetReport? = null,
) : OperationResult
