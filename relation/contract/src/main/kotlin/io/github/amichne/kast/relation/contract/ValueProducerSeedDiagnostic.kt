package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Failed acquisition keeps its actual operation and original finite failure. No mismatch is inferred. */
sealed interface ValueProducerSeedDiagnostic {
    data object NotAcquired : ValueProducerSeedDiagnostic

    data class InitialExecution(
        val enclosing: RelationEndpoint,
        val anchor: ExactDeclarationTextRange,
        val failure: ValueProducerExecutionFailure,
    ) : ValueProducerSeedDiagnostic
}

sealed interface ValueProducerExecutionFailure {
    data class Native(val cause: ValueFlowRejection) : ValueProducerExecutionFailure

    data class Unsupported(
        val cause: ValueFlowUnsupportedCause,
        val callbackFailures: List<ValueCallbackInputFailure>,
    ) : ValueProducerExecutionFailure
}
