package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Snapshot-local reaching definitions, not compiler SSA identifiers or runtime reachability claims. */
sealed interface ValueLocalVersion {
    data object Unversioned : ValueLocalVersion

    @ConsistentCopyVisibility
    data class Defined
    private constructor(
        val execution: ValueExecutionOwner,
        val declaration: ExactDeclarationTextRange,
        val block: ExactDeclarationTextRange,
        val definition: ExactDeclarationTextRange,
        val rhs: ExactDeclarationTextRange,
        val end: End,
    ) : ValueLocalVersion {
        fun admitsRead(range: ExactDeclarationTextRange): Boolean =
            range.startInclusive >= definition.endExclusive &&
                when (val limit = end) {
                    is End.NextWrite ->
                        range.endExclusive <= limit.range.startInclusive || limit.rhs.containsValueRange(range)
                    is End.BlockEnd -> range.endExclusive <= limit.range.endExclusive
                }

        companion object {
            /** Native resolution proves the binding and the complete, noncaptured straight-line write inventory. */
            fun fromCompiler(
                execution: ValueExecutionOwner,
                declaration: ExactDeclarationTextRange,
                block: ExactDeclarationTextRange,
                definition: ExactDeclarationTextRange,
                rhs: ExactDeclarationTextRange,
                end: End,
            ): Refinement<Defined, ValueSiteFailure> =
                when {
                    execution.context != ValueExecutionContext.Declaration ->
                        Refinement.Rejected(ValueSiteFailure.EXECUTION_OWNER_MISMATCH)
                    !execution.range.containsValueRange(block) ||
                        !block.containsValueRange(declaration) ||
                        !block.containsValueRange(definition) ||
                        !definition.containsValueRange(rhs) ||
                        definition != declaration && declaration.endExclusive > definition.startInclusive ||
                        !end.admitsDefinition(block, definition) ->
                        Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER)
                    else -> Refinement.Refined(Defined(execution, declaration, block, definition, rhs, end))
                }
        }
    }

    sealed interface End {
        val range: ExactDeclarationTextRange

        data class NextWrite(override val range: ExactDeclarationTextRange, val rhs: ExactDeclarationTextRange) : End

        data class BlockEnd(override val range: ExactDeclarationTextRange) : End
    }
}

private fun ValueLocalVersion.End.admitsDefinition(
    block: ExactDeclarationTextRange,
    definition: ExactDeclarationTextRange,
): Boolean =
    when (this) {
        is ValueLocalVersion.End.BlockEnd -> range == block
        is ValueLocalVersion.End.NextWrite ->
            block.containsValueRange(range) &&
                definition.endExclusive <= range.startInclusive &&
                range.containsValueRange(rhs)
    }

internal fun ValueLocalVersion.admitsSite(
    role: ValueRole,
    range: ExactDeclarationTextRange,
    execution: ValueExecutionOwner,
): Boolean =
    when (this) {
        ValueLocalVersion.Unversioned -> true
        is ValueLocalVersion.Defined ->
            role == ValueRole.LocalBinding && definition == range && this.execution == execution
    }

internal fun ValueLocalVersion.admitsRead(source: ValueSite, target: ValueSite): Boolean =
    when (this) {
        ValueLocalVersion.Unversioned -> true
        is ValueLocalVersion.Defined ->
            execution == source.executionOwner && execution == target.executionOwner && admitsRead(target.range)
    }
