package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

enum class ReviewedStringListRule {
    KOTLIN_2_3_0_DIRECT_VARARG_STRINGS_V1
}

/** Closed sections include the array view helpers used by the direct vararg implementation. */
enum class ReviewedStringListSourceSection(
    val sourceEntry: String,
    val sourceEntrySha256: String,
    val firstLine: Int,
    val lastLine: Int,
) {
    LIST_OF(
        "commonMain/kotlin/collections/Collections.kt",
        "8f99448da740e96343a5c9a37aa584000a6c914773b089f590e614cc0b0fdd6b",
        LIST_OF_LINE,
        LIST_OF_LINE,
    ),
    ARRAY_AS_LIST(
        "jvmMain/generated/_ArraysJvm.kt",
        "11c62590490748672aed289ff84aa4c74c2adbf961e701e95d69742b0518fef5",
        ARRAY_AS_LIST_FIRST,
        ARRAY_AS_LIST_LAST,
    ),
    ARRAYS_UTIL_AS_LIST(
        "jvmMain/kotlin/collections/ArraysUtilJVM.java",
        "94b06874e3859a8f096d1f6aa2fe29bd54aef2ad2ec39e811f5c15f1d568641e",
        ARRAYS_UTIL_FIRST,
        ARRAYS_UTIL_LAST,
    ),
}

class ReviewedStringListArtifact
private constructor(val binary: DependencyArtifactDigest, val sources: DependencyArtifactDigest) {
    val rule = ReviewedStringListRule.KOTLIN_2_3_0_DIRECT_VARARG_STRINGS_V1
    val sourceSections: List<ReviewedStringListSourceSection>
        get() = ReviewedStringListSourceSection.entries

    val classes: List<ReviewedClassEvidence> =
        Collections.unmodifiableList(
            listOf(
                ReviewedClassEvidence(
                    "kotlin/collections/CollectionsKt.class",
                    "516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770",
                ),
                ReviewedClassEvidence(
                    "kotlin/collections/CollectionsKt__CollectionsKt.class",
                    "4f7c41e7e4fed9faf50a3f24415e32f502d247ae6970b701002cae7128356ee2",
                ),
                ReviewedClassEvidence(
                    "kotlin/collections/ArraysKt.class",
                    "76a3f1b2f8341ceb3d595cb4a827171bc632b711c9037f48d04c6260dfbf3fed",
                ),
                ReviewedClassEvidence(
                    "kotlin/collections/ArraysKt___ArraysJvmKt.class",
                    "c953c2eec8057b0621601d50e3d4b7b6187b8d37c5e6633588b1c3efe59e92a6",
                ),
                ReviewedClassEvidence(
                    "kotlin/collections/ArraysUtilJVM.class",
                    "e77d73a588507edb74494536a6ed321e9a291a9aba24704106da1ca5c70dc6d4",
                ),
            )
        )
    val retainedBytes: Long
        get() = STRING_ARTIFACT_BYTES

    companion object {
        fun admit(
            formal: ReviewedVarargFormal,
            binary: DependencyArtifactDigest,
            sources: DependencyArtifactDigest,
        ): Refinement<ReviewedStringListArtifact, ReviewedDistinctKeyFailure> =
            if (
                formal.position.value == 0 && binary.value == STRING_LIST_BINARY && sources.value == STRING_LIST_SOURCES
            )
                Refinement.Refined(ReviewedStringListArtifact(binary, sources))
            else distinctKeyRejected(ReviewedDistinctKeyFailure.ARTIFACT_MISMATCH)
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedStringListArtifact && binary == other.binary && sources == other.sources

    override fun hashCode(): Int = 31 * binary.hashCode() + sources.hashCode()
}

private const val STRING_LIST_BINARY = "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"
private const val STRING_LIST_SOURCES = "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"
private const val STRING_ARTIFACT_BYTES = 8192L
private const val LIST_OF_LINE = 81
private const val ARRAY_AS_LIST_FIRST = 133
private const val ARRAY_AS_LIST_LAST = 135
private const val ARRAYS_UTIL_FIRST = 12
private const val ARRAYS_UTIL_LAST = 14
