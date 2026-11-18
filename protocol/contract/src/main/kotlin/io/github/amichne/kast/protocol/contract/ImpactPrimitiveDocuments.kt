package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ImpactPrimitiveInputDocument {
    DISPATCH_RECEIVER,
    ARGUMENT_ZERO,
    ARGUMENT_ONE,
}

@Serializable
enum class ImpactPrimitiveSourceEvidenceDocument {
    REVIEWED_REFERENCE_FOR_BUILTIN_METADATA,
    MATCHED_LIBRARY_SOURCE_ARCHIVE,
}

@Serializable
@SerialName("PRIMITIVE_OPERATION")
data class ImpactPrimitiveArtifactDocument(
    val sourceEvidence: ImpactPrimitiveSourceEvidenceDocument,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val binaryArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val sourceArtifactSha256: ProtocolText,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    override val sourceEntrySha256: ProtocolText,
) : ImpactReviewedArtifactsDocument

internal fun ImpactReviewedValueTransferDocument.admitsPrimitive(input: ImpactPrimitiveInputDocument): Boolean {
    val proof = binding.authority as? ImpactReviewedCallValueBindingDocument ?: return false
    if (!target.isOrdinaryResult(proof) || !source.matchesOrdinaryOwner(proof)) return false
    if (
        source.valuePath.values.isNotEmpty() ||
            target.valuePath.values.isNotEmpty() ||
            !source.conditions.hasSameConditions(target.conditions)
    )
        return false
    return when (input) {
        ImpactPrimitiveInputDocument.DISPATCH_RECEIVER ->
            (source.role as? ImpactValueRoleDocument.Receiver)?.let {
                it.kind == ImpactReceiverKindDocument.DISPATCH && it.invocation == proof.invocation
            } == true
        ImpactPrimitiveInputDocument.ARGUMENT_ZERO -> source.matchesArgument(proof, 0)
        ImpactPrimitiveInputDocument.ARGUMENT_ONE -> source.matchesArgument(proof, 1)
    }
}

internal enum class ImpactPrimitiveFamily(
    val fileSuffix: String,
    val parameters: List<String>,
    val sourceDigest: String,
) {
    MEMBER(
        "!/kotlin/kotlin.kotlin_builtins",
        listOf("kotlin/Int"),
        "0a70201e18d4f267a65f565985260fe3be2a252cc1177c9f944869d001dadf36",
    ),
    MATH(
        "!/kotlin/math/MathKt.class",
        listOf("kotlin/Int", "kotlin/Int"),
        "0c421ce335e168edf1ad14c8b1bb97e0051b61573e9ead4700569d1f2014dd65",
    );

    val sourceEvidence: ImpactPrimitiveSourceEvidenceDocument
        get() =
            when (this) {
                MEMBER -> ImpactPrimitiveSourceEvidenceDocument.REVIEWED_REFERENCE_FOR_BUILTIN_METADATA
                MATH -> ImpactPrimitiveSourceEvidenceDocument.MATCHED_LIBRARY_SOURCE_ARCHIVE
            }

    fun admits(input: ImpactPrimitiveInputDocument) =
        when (input) {
            ImpactPrimitiveInputDocument.DISPATCH_RECEIVER -> this == MEMBER
            ImpactPrimitiveInputDocument.ARGUMENT_ZERO -> true
            ImpactPrimitiveInputDocument.ARGUMENT_ONE -> this == MATH
        }
}

internal enum class ImpactPrimitiveRule(
    val rule: ImpactReviewedValueRuleDocument,
    val callable: String,
    val family: ImpactPrimitiveFamily,
) {
    INT_MINUS(
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MINUS_V1,
        "kotlin.Int.minus",
        ImpactPrimitiveFamily.MEMBER,
    ),
    INT_PLUS(ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_PLUS_V1, "kotlin.Int.plus", ImpactPrimitiveFamily.MEMBER),
    INT_TIMES(
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_TIMES_V1,
        "kotlin.Int.times",
        ImpactPrimitiveFamily.MEMBER,
    ),
    INT_MIN(ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MIN_V1, "kotlin.math.min", ImpactPrimitiveFamily.MATH),
    INT_MAX(ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MAX_V1, "kotlin.math.max", ImpactPrimitiveFamily.MATH),
}

internal fun ImpactReviewedValueRuleDocument.primitiveRule() =
    ImpactPrimitiveRule.entries.singleOrNull { it.rule == this }
