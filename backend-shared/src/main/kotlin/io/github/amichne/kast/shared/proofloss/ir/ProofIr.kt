package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.api.contract.NonEmptyList
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.shared.proofloss.model.ArgumentIndex
import io.github.amichne.kast.shared.proofloss.model.BoundaryId
import io.github.amichne.kast.shared.proofloss.model.PredicateId
import io.github.amichne.kast.shared.proofloss.model.ProofCallableKey

@JvmInline
value class SourceOffset private constructor(val value: Int) : Comparable<SourceOffset> {
    override fun compareTo(other: SourceOffset) = value.compareTo(other.value)

    companion object {
        fun valid(value: Int): SourceOffset {
            require(value >= 0); return SourceOffset(value)
        }
    }
}

data class ProofSourceSpan(
    val filePath: NormalizedPath,
    val startOffset: SourceOffset,
    val endOffset: SourceOffset,
) {
    init {
        require(endOffset >= startOffset)
    }
}

data class ProofFunctionId(
    val filePath: NormalizedPath,
    val declarationOffset: SourceOffset,
) : Comparable<ProofFunctionId> {
    override fun compareTo(other: ProofFunctionId) =
        compareValuesBy(this, other, { it.filePath }, { it.declarationOffset })
}

data class TrackedValueId(
    val filePath: NormalizedPath,
    val declarationOffset: SourceOffset,
)

data class FunctionIr(
    val id: ProofFunctionId,
    val parameters: Set<TrackedValueId>,
    val body: Block,
)

data class Block(val statements: List<Statement>) {
    constructor(vararg statements: Statement) : this(statements.toList())
}

sealed interface Statement {
    val location: ProofSourceSpan
}

data class LetStatement(
    val target: TrackedValueId,
    val expression: ValueExpression,
    override val location: ProofSourceSpan,
) : Statement

data class PredicateCondition(
    val predicate: PredicateId,
    val subject: TrackedValueId,
    val conditionTrueMeansPredicate: Boolean,
    val location: ProofSourceSpan,
)

data class IfStatement(
    val condition: PredicateCondition,
    val thenBranch: Block,
    val elseBranch: Block = Block(),
    override val location: ProofSourceSpan = condition.location,
) : Statement

data class BoundaryCall(
    val boundary: BoundaryId,
    val arguments: Map<ArgumentIndex, TrackedValueId>,
    override val location: ProofSourceSpan,
) : Statement

enum class ExitKind { RETURN, THROW }
data class ExitStatement(
    val kind: ExitKind,
    override val location: ProofSourceSpan,
) : Statement

data class NoOpStatement(override val location: ProofSourceSpan) : Statement
sealed interface ValueExpression {
    data class Alias(val source: TrackedValueId) : ValueExpression
    data class Materialize(
        val predicate: PredicateId,
        val source: TrackedValueId,
        val callable: ProofCallableKey,
    ) : ValueExpression
}

fun interface ProofIrExtractor<S> {
    fun extract(source: S): ExtractionResult
}

sealed interface ExtractionResult {
    data class Supported(val function: FunctionIr) : ExtractionResult
    data class Unsupported(
        val functionId: ProofFunctionId,
        val reasons: NonEmptyList<UnsupportedReason>,
    ) : ExtractionResult
}

sealed interface UnsupportedReason {
    val location: ProofSourceSpan?

    data class MutableTrackedValue(
        override val location: ProofSourceSpan,
        val value: TrackedValueId,
    ) : UnsupportedReason

    data class Loop(override val location: ProofSourceSpan) : UnsupportedReason
    data class NestedLambda(override val location: ProofSourceSpan) : UnsupportedReason
    data class NonDirectBoundaryArgument(override val location: ProofSourceSpan) : UnsupportedReason
    data class UnresolvedCall(override val location: ProofSourceSpan) : UnsupportedReason
    data class UnprovenMaterializationSuccess(override val location: ProofSourceSpan) : UnsupportedReason
    data class UnsupportedArgumentMapping(override val location: ProofSourceSpan) : UnsupportedReason
    data class UnsupportedControlFlow(override val location: ProofSourceSpan) : UnsupportedReason
}
