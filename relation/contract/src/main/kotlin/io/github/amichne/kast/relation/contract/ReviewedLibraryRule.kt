package io.github.amichne.kast.relation.contract

/** The compiler contract describes invocation independently of the reviewed result rule. */
sealed interface ReviewedCallbackInvocationEvidence {
    data object Unestablished : ReviewedCallbackInvocationEvidence

    data class Declared internal constructor(val binding: DeclaredCallbackValueBinding) :
        ReviewedCallbackInvocationEvidence
}

/** A closed operation set separates callback returns from receiver identity. */
enum class ReviewedLibraryOperation {
    ITERABLE_MAP,
    LET,
    ALSO,
}

/** Each reviewed rule belongs to one operation and an exact artifact pair. */
enum class ReviewedLibraryRule(val operation: ReviewedLibraryOperation) {
    KOTLIN_2_3_0_ITERABLE_MAP_V1(ReviewedLibraryOperation.ITERABLE_MAP),
    KOTLIN_2_4_21_ITERABLE_MAP_V1(ReviewedLibraryOperation.ITERABLE_MAP),
    KOTLIN_2_3_0_LET_V1(ReviewedLibraryOperation.LET),
    KOTLIN_2_3_0_ALSO_V1(ReviewedLibraryOperation.ALSO);

    val sourceEntry: String
        get() =
            when (this) {
                KOTLIN_2_3_0_ITERABLE_MAP_V1,
                KOTLIN_2_4_21_ITERABLE_MAP_V1 -> "commonMain/generated/_Collections.kt"
                KOTLIN_2_3_0_LET_V1,
                KOTLIN_2_3_0_ALSO_V1 -> "commonMain/kotlin/util/Standard.kt"
            }

    val sourceEntrySha256: String
        get() =
            when (this) {
                KOTLIN_2_3_0_ITERABLE_MAP_V1 -> "03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970"
                KOTLIN_2_4_21_ITERABLE_MAP_V1 -> "aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c"
                KOTLIN_2_3_0_LET_V1,
                KOTLIN_2_3_0_ALSO_V1 -> "86b7320d868d66492d277ebb5d5d8ad78da074d592dacf32f186a83d8d3196a0"
            }

    val facadeSha256: String
        get() =
            when (this) {
                KOTLIN_2_3_0_ITERABLE_MAP_V1 -> "516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770"
                KOTLIN_2_4_21_ITERABLE_MAP_V1 -> "7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878"
                KOTLIN_2_3_0_LET_V1,
                KOTLIN_2_3_0_ALSO_V1 -> "f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05"
            }

    val implementationSha256: String
        get() =
            when (this) {
                KOTLIN_2_3_0_ITERABLE_MAP_V1 -> "fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39"
                KOTLIN_2_4_21_ITERABLE_MAP_V1 -> "e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296"
                KOTLIN_2_3_0_LET_V1,
                KOTLIN_2_3_0_ALSO_V1 -> "bfdad3872af89a51041163c03afda592baea4e394a8b1665de5b7547f4f43ce0"
            }
}
