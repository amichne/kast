package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

enum class ReviewedPrimitiveFamily(
    val fileSuffix: String,
    val sourceEntry: String,
    val sourceDigest: String,
    val parameterTypes: List<String>,
) {
    MEMBER(
        "!/kotlin/kotlin.kotlin_builtins",
        "commonMain/kotlin/Primitives.kt",
        "0a70201e18d4f267a65f565985260fe3be2a252cc1177c9f944869d001dadf36",
        listOf("kotlin/Int"),
    ),
    MATH(
        "!/kotlin/math/MathKt.class",
        "jvmMain/kotlin/util/MathJVM.kt",
        "0c421ce335e168edf1ad14c8b1bb97e0051b61573e9ead4700569d1f2014dd65",
        listOf("kotlin/Int", "kotlin/Int"),
    );

    fun admits(input: ReviewedPrimitiveInput) =
        when (input) {
            ReviewedPrimitiveInput.DISPATCH_RECEIVER -> this == MEMBER
            ReviewedPrimitiveInput.ARGUMENT_ZERO -> true
            ReviewedPrimitiveInput.ARGUMENT_ONE -> this == MATH
        }
}

enum class ReviewedPrimitiveRule(
    override val operation: ReviewedLibraryOperation,
    val callable: String,
    val family: ReviewedPrimitiveFamily,
    val section: ReviewedSourceSection,
) : ReviewedRuleEvidence {
    KOTLIN_2_3_0_INT_MINUS_V1(
        ReviewedLibraryOperation.INT_MINUS,
        "kotlin.Int.minus",
        ReviewedPrimitiveFamily.MEMBER,
        ReviewedSourceSection.INT_MINUS_DECLARATION,
    ),
    KOTLIN_2_3_0_INT_PLUS_V1(
        ReviewedLibraryOperation.INT_PLUS,
        "kotlin.Int.plus",
        ReviewedPrimitiveFamily.MEMBER,
        ReviewedSourceSection.INT_PLUS_DECLARATION,
    ),
    KOTLIN_2_3_0_INT_TIMES_V1(
        ReviewedLibraryOperation.INT_TIMES,
        "kotlin.Int.times",
        ReviewedPrimitiveFamily.MEMBER,
        ReviewedSourceSection.INT_TIMES_DECLARATION,
    ),
    KOTLIN_2_3_0_INT_MIN_V1(
        ReviewedLibraryOperation.INT_MIN,
        "kotlin.math.min",
        ReviewedPrimitiveFamily.MATH,
        ReviewedSourceSection.INT_MIN_DECLARATION,
    ),
    KOTLIN_2_3_0_INT_MAX_V1(
        ReviewedLibraryOperation.INT_MAX,
        "kotlin.math.max",
        ReviewedPrimitiveFamily.MATH,
        ReviewedSourceSection.INT_MAX_DECLARATION,
    ),
}

class ReviewedPrimitiveArtifact
private constructor(
    override val binaryArtifact: DependencyArtifactDigest,
    override val sourceArtifact: DependencyArtifactDigest,
    override val rule: ReviewedPrimitiveRule,
) : ReviewedArtifactEvidence {
    override val sourceEntry = rule.family.sourceEntry
    override val sourceEntrySha256 = rule.family.sourceDigest
    override val sourceSections = listOf(rule.section)
    override val helpers = emptyList<ReviewedLibraryHelper>()

    companion object {
        /** The source digest identifies the reviewed reference text. Only the selected binary was read locally. */
        fun fromBuiltinMetadata(
            selected: ReviewedOrdinaryLibraryCallable,
            binary: DependencyArtifactDigest,
        ): Refinement<ReviewedPrimitiveArtifact, ReviewedCallbackValueFailure> {
            if (selected.primitiveRule?.family != ReviewedPrimitiveFamily.MEMBER)
                return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            return when (val sources = DependencyArtifactDigest.parse(REVIEWED_STDLIB_230_SOURCE_DIGEST)) {
                is Refinement.Refined -> admit(selected, binary, sources.value)
                is Refinement.Rejected -> sources
            }
        }

        internal fun admit(
            selected: ReviewedOrdinaryLibraryCallable,
            binary: DependencyArtifactDigest,
            sources: DependencyArtifactDigest,
        ): Refinement<ReviewedPrimitiveArtifact, ReviewedCallbackValueFailure> {
            val archive = ReviewedLibraryArtifact.admit(binary, sources)
            if (
                archive !is Refinement.Refined || archive.value.rule != ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1
            )
                return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH)
            val rule =
                selected.primitiveRule ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            return Refinement.Refined(ReviewedPrimitiveArtifact(binary, sources, rule))
        }
    }

    override fun equals(other: Any?) = other is ReviewedPrimitiveArtifact && rule == other.rule

    override fun hashCode() = rule.hashCode()
}

enum class ReviewedPrimitiveInput {
    DISPATCH_RECEIVER,
    ARGUMENT_ZERO,
    ARGUMENT_ONE,
}

internal fun ReviewedCallValueBinding.primitiveInput(
    source: ValueSite
): Refinement<ReviewedPrimitiveInput, ValueFlowUnsupportedCause> {
    if (
        artifact !is ReviewedPrimitiveArtifact ||
            source.enclosing != enclosing ||
            source.executionOwner != invocation.executionOwner
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    if (source.valuePath.segments.isNotEmpty() || !invocation.range.containsValueRange(source.range))
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    val input =
        when (val selected = invocation.primitiveInput(source.role)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    if (!artifact.rule.family.admits(input)) return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    return Refinement.Refined(input)
}

private fun ValueInvocation.primitiveInput(
    role: ValueRole
): Refinement<ReviewedPrimitiveInput, ValueFlowUnsupportedCause> {
    val input =
        when (role) {
            is ValueRole.Receiver ->
                if (role.kind == ValueReceiverKind.DISPATCH && role.call == this)
                    ReviewedPrimitiveInput.DISPATCH_RECEIVER
                else return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
            is ValueRole.Argument -> {
                if (role.call != this) return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
                when (role.position.value) {
                    0 -> ReviewedPrimitiveInput.ARGUMENT_ZERO
                    1 -> ReviewedPrimitiveInput.ARGUMENT_ONE
                    else -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
                }
            }
            else -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
        }
    return Refinement.Refined(input)
}
