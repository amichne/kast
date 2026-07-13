package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.shared.proofloss.model.ArgumentIndex
import io.github.amichne.kast.shared.proofloss.model.BoundaryId

sealed interface Statement {
    val location: SourceSpan

    data class Let(
        val target: TrackedValueId,
        val expression: ValueExpression,
        override val location: SourceSpan,
    ) : Statement

    data class If(
        val condition: PredicateCondition,
        val thenBranch: Block,
        val elseBranch: Block = Block(),
        override val location: SourceSpan = condition.location,
    ) : Statement

    data class BoundaryCall(
        val boundary: BoundaryId,
        val arguments: Map<ArgumentIndex, TrackedValueId>,
        override val location: SourceSpan,
    ) : Statement

    data class Exit(
        val kind: ExitKind,
        override val location: SourceSpan,
    ) : Statement

    data class NoOp(override val location: SourceSpan) : Statement
}
