package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** A finite rule identifies either reviewed JVM behavior or a builtins declaration. */
sealed interface ReviewedRuleEvidence {
    val operation: ReviewedLibraryOperation
    val name: String
}

/** Shared artifact identity does not turn declaration metadata into an implementation body. */
sealed interface ReviewedArtifactEvidence {
    val binaryArtifact: DependencyArtifactDigest
    val sourceArtifact: DependencyArtifactDigest
    val rule: ReviewedRuleEvidence
    val sourceEntry: String
    val sourceEntrySha256: String
    val sourceSections: List<ReviewedSourceSection>
    val helpers: List<ReviewedLibraryHelper>
}

data class ReviewedMetadataEvidence(val entry: String, val sha256: String)

enum class ReviewedEntryDeclarationRule(override val operation: ReviewedLibraryOperation) : ReviewedRuleEvidence {
    KOTLIN_2_3_0_MAP_ENTRY_KEY_V1(ReviewedLibraryOperation.MAP_ENTRY_KEY),
    KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1(ReviewedLibraryOperation.MAP_ENTRY_VALUE),
}

/** The reviewed declaration names a slot, without a concrete getter body or purity claim. */
class ReviewedEntryDeclarationArtifact
private constructor(
    override val binaryArtifact: DependencyArtifactDigest,
    override val sourceArtifact: DependencyArtifactDigest,
    override val rule: ReviewedEntryDeclarationRule,
) : ReviewedArtifactEvidence {
    override val sourceEntry = "commonMain/kotlin/Collections.kt"
    override val sourceEntrySha256 = "a972160cdcfa4c563abeaf0d10992080470713315a2028de4c421a761a0b3850"
    override val sourceSections = listOf(ReviewedSourceSection.MAP_ENTRY_DECLARATION)
    override val helpers = emptyList<ReviewedLibraryHelper>()
    val metadata =
        ReviewedMetadataEvidence(
            entry = "kotlin/collections/collections.kotlin_builtins",
            sha256 = "e91b5fc8241f903f0cd2c6e3d038c10a9c74a8f22659dfa9268e21afcca70bf1",
        )

    companion object {
        internal fun admit(
            selected: ReviewedOrdinaryLibraryCallable,
            binaryArtifact: DependencyArtifactDigest,
            sourceArtifact: DependencyArtifactDigest,
        ): Refinement<ReviewedEntryDeclarationArtifact, ReviewedCallbackValueFailure> {
            val archive =
                when (val value = ReviewedLibraryArtifact.admit(binaryArtifact, sourceArtifact)) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return value
                }
            if (archive.rule != ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1)
                return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH)
            val rule =
                when (selected.operation) {
                    ReviewedLibraryOperation.MAP_ENTRY_KEY -> ReviewedEntryDeclarationRule.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1
                    ReviewedLibraryOperation.MAP_ENTRY_VALUE ->
                        ReviewedEntryDeclarationRule.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1
                    else -> return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
                }
            return Refinement.Refined(ReviewedEntryDeclarationArtifact(binaryArtifact, sourceArtifact, rule))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedEntryDeclarationArtifact &&
            binaryArtifact == other.binaryArtifact &&
            sourceArtifact == other.sourceArtifact &&
            rule == other.rule

    override fun hashCode(): Int = listOf(binaryArtifact, sourceArtifact, rule).hashCode()
}
