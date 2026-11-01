package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Content contribution is a derived relationship, not identity of the containing String. */
enum class ValueStringContentInput {
    STRING,
    NULLABLE_STRING,
}

enum class ValueStringNullAlternative {
    NOT_NULLABLE,
    CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT,
}

@ConsistentCopyVisibility
data class ValueStringContent
private constructor(
    val templateRange: ExactDeclarationTextRange,
    val entryRange: ExactDeclarationTextRange,
    val inputRange: ExactDeclarationTextRange,
    val input: ValueStringContentInput,
) : ValueTransferEvidence {
    val nullAlternative: ValueStringNullAlternative
        get() =
            when (input) {
                ValueStringContentInput.STRING -> ValueStringNullAlternative.NOT_NULLABLE
                ValueStringContentInput.NULLABLE_STRING ->
                    ValueStringNullAlternative.CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT
            }

    companion object {
        /** The compiler adapter establishes String type and the exact embedded expression result. */
        fun fromCompiler(
            templateRange: ExactDeclarationTextRange,
            entryRange: ExactDeclarationTextRange,
            inputRange: ExactDeclarationTextRange,
            input: ValueStringContentInput,
        ): Refinement<ValueStringContent, ValueTransferFailure> =
            if (
                templateRange.containsValueRange(entryRange) &&
                    entryRange.containsValueRange(inputRange) &&
                    distinctRanges(templateRange, entryRange, inputRange)
            )
                Refinement.Refined(ValueStringContent(templateRange, entryRange, inputRange, input))
            else Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
    }

    internal fun admitsContent(source: ValueSite, target: ValueSite, kind: ValueTransferKind): Boolean =
        kind == ValueTransferKind.STRING_CONTENT &&
            inputRange.containsValueRange(source.range) &&
            source.valuePath.segments.isEmpty() &&
            target.valuePath.segments.isEmpty() &&
            source.executionOwner == target.executionOwner &&
            source.callStack == target.callStack &&
            target.range == templateRange &&
            target.role == ValueRole.ExpressionResult &&
            admitsConditions(source, target)

    private fun admitsConditions(source: ValueSite, target: ValueSite): Boolean =
        when (input) {
            ValueStringContentInput.STRING -> source.conditions == target.conditions
            ValueStringContentInput.NULLABLE_STRING ->
                target.conditions.facts.lastOrNull().let { condition ->
                    condition is ValueCondition.StringContentNonNull &&
                        condition.origin.matches(source) &&
                        condition.useRange == templateRange &&
                        condition.entryRange == entryRange
                }
        }
}

private fun distinctRanges(
    template: ExactDeclarationTextRange,
    entry: ExactDeclarationTextRange,
    input: ExactDeclarationTextRange,
) = template != entry && entry != input
