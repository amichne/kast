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
    ITERABLE_GROUP_BY,
    ITERABLE_GROUP_BY_TRANSFORM,
    ITERABLE_FILTER,
    ITERABLE_MAP_NOT_NULL,
    MAP_VALUES,
    MAP_ENTRY_KEY,
    MAP_ENTRY_VALUE,
    MAP_ENTRY_COMPONENT1,
    MAP_ENTRY_COMPONENT2,
    ITERABLE_SORTED_BY,
    ITERABLE_ASSOCIATE,
    TO,
    PAIR_CONSTRUCTOR,
    PAIR_FIRST,
    PAIR_SECOND,
    RUN_TOP_LEVEL,
    RUN_RECEIVER,
    WITH,
    APPLY,
    LET,
    ALSO,
}

/** Each reviewed rule belongs to one operation and an exact artifact pair. */
enum class ReviewedLibraryRule(override val operation: ReviewedLibraryOperation) : ReviewedRuleEvidence {
    KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1),
    KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2),
    KOTLIN_2_3_0_MAP_VALUES_V1(ReviewedLibraryOperation.MAP_VALUES),
    KOTLIN_2_3_0_ITERABLE_FILTER_V1(ReviewedLibraryOperation.ITERABLE_FILTER),
    KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1(ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL),
    KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1(ReviewedLibraryOperation.ITERABLE_GROUP_BY),
    KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1(ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM),
    KOTLIN_2_3_0_ITERABLE_MAP_V1(ReviewedLibraryOperation.ITERABLE_MAP),
    KOTLIN_2_4_21_ITERABLE_MAP_V1(ReviewedLibraryOperation.ITERABLE_MAP),
    KOTLIN_2_3_0_RUN_TOP_LEVEL_V1(ReviewedLibraryOperation.RUN_TOP_LEVEL),
    KOTLIN_2_3_0_RUN_RECEIVER_V1(ReviewedLibraryOperation.RUN_RECEIVER),
    KOTLIN_2_3_0_WITH_V1(ReviewedLibraryOperation.WITH),
    KOTLIN_2_3_0_APPLY_V1(ReviewedLibraryOperation.APPLY),
    KOTLIN_2_3_0_LET_V1(ReviewedLibraryOperation.LET),
    KOTLIN_2_3_0_ALSO_V1(ReviewedLibraryOperation.ALSO),
    KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1(ReviewedLibraryOperation.ITERABLE_SORTED_BY),
    KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1(ReviewedLibraryOperation.ITERABLE_ASSOCIATE),
    KOTLIN_2_3_0_TO_V1(ReviewedLibraryOperation.TO),
    KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1(ReviewedLibraryOperation.PAIR_CONSTRUCTOR),
    KOTLIN_2_3_0_PAIR_FIRST_V1(ReviewedLibraryOperation.PAIR_FIRST),
    KOTLIN_2_3_0_PAIR_SECOND_V1(ReviewedLibraryOperation.PAIR_SECOND);

    val sourceEntry: String
        get() =
            when (this) {
                KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1,
                KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1,
                KOTLIN_2_3_0_MAP_VALUES_V1 -> "commonMain/kotlin/collections/Maps.kt"
                KOTLIN_2_3_0_TO_V1 -> "commonMain/kotlin/util/Tuples.kt"
                KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
                KOTLIN_2_3_0_PAIR_FIRST_V1,
                KOTLIN_2_3_0_PAIR_SECOND_V1 -> "commonMain/kotlin/util/Tuples.kt"
                KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1,
                KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1,
                KOTLIN_2_3_0_ITERABLE_FILTER_V1,
                KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1,
                KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1,
                KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1,
                KOTLIN_2_3_0_ITERABLE_MAP_V1,
                KOTLIN_2_4_21_ITERABLE_MAP_V1 -> "commonMain/generated/_Collections.kt"
                KOTLIN_2_3_0_RUN_TOP_LEVEL_V1,
                KOTLIN_2_3_0_RUN_RECEIVER_V1,
                KOTLIN_2_3_0_WITH_V1,
                KOTLIN_2_3_0_APPLY_V1,
                KOTLIN_2_3_0_LET_V1,
                KOTLIN_2_3_0_ALSO_V1 -> "commonMain/kotlin/util/Standard.kt"
            }

    val sourceEntrySha256: String
        get() =
            when (this) {
                KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1,
                KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1,
                KOTLIN_2_3_0_MAP_VALUES_V1 -> "1eed0a8e3180aa82e46bcb0049efcaa80c4b6bdee50aef9754b848ee171cdde7"
                KOTLIN_2_3_0_TO_V1 -> "1eaf3b3334dbd646a6b93dbe31db7adba72fdb92e8a5d3ea6b249aff5a7d9e9f"
                KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
                KOTLIN_2_3_0_PAIR_FIRST_V1,
                KOTLIN_2_3_0_PAIR_SECOND_V1 -> "1eaf3b3334dbd646a6b93dbe31db7adba72fdb92e8a5d3ea6b249aff5a7d9e9f"
                KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1,
                KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1,
                KOTLIN_2_3_0_ITERABLE_FILTER_V1,
                KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1,
                KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1,
                KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1,
                KOTLIN_2_3_0_ITERABLE_MAP_V1 -> "03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970"
                KOTLIN_2_4_21_ITERABLE_MAP_V1 -> "aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c"
                KOTLIN_2_3_0_RUN_TOP_LEVEL_V1,
                KOTLIN_2_3_0_RUN_RECEIVER_V1,
                KOTLIN_2_3_0_WITH_V1,
                KOTLIN_2_3_0_APPLY_V1,
                KOTLIN_2_3_0_LET_V1,
                KOTLIN_2_3_0_ALSO_V1 -> "86b7320d868d66492d277ebb5d5d8ad78da074d592dacf32f186a83d8d3196a0"
            }

    val facadeSha256: String
        get() =
            when (this) {
                KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1,
                KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1,
                KOTLIN_2_3_0_MAP_VALUES_V1 -> "520e0f6d39db8a20da2ae0b5098ec91be861892695db7a40fef3480a1a7a80b4"
                KOTLIN_2_3_0_TO_V1 -> "7d7ae7592527ece70344f2aed7d546c4c44085472154b88fd2e119fef0cd82b0"
                KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
                KOTLIN_2_3_0_PAIR_FIRST_V1,
                KOTLIN_2_3_0_PAIR_SECOND_V1 -> "8fed0fa88eb2df5723812a95330dee6bc91fd66b13906acb7906f9a813da9736"
                KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1,
                KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1,
                KOTLIN_2_3_0_ITERABLE_FILTER_V1,
                KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1,
                KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1,
                KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1,
                KOTLIN_2_3_0_ITERABLE_MAP_V1 -> "516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770"
                KOTLIN_2_4_21_ITERABLE_MAP_V1 -> "7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878"
                KOTLIN_2_3_0_RUN_TOP_LEVEL_V1,
                KOTLIN_2_3_0_RUN_RECEIVER_V1,
                KOTLIN_2_3_0_WITH_V1,
                KOTLIN_2_3_0_APPLY_V1,
                KOTLIN_2_3_0_LET_V1,
                KOTLIN_2_3_0_ALSO_V1 -> "f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05"
            }

    val implementationSha256: String
        get() =
            when (this) {
                KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1,
                KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1,
                KOTLIN_2_3_0_MAP_VALUES_V1 -> "bb2bb2582743754096256a7cb4cd3a219bfb498a0de1a12f725e7e999227d389"
                KOTLIN_2_3_0_TO_V1 -> "7d7ae7592527ece70344f2aed7d546c4c44085472154b88fd2e119fef0cd82b0"
                KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
                KOTLIN_2_3_0_PAIR_FIRST_V1,
                KOTLIN_2_3_0_PAIR_SECOND_V1 -> "8fed0fa88eb2df5723812a95330dee6bc91fd66b13906acb7906f9a813da9736"
                KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1,
                KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1,
                KOTLIN_2_3_0_ITERABLE_FILTER_V1,
                KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1,
                KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1,
                KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1,
                KOTLIN_2_3_0_ITERABLE_MAP_V1 -> "fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39"
                KOTLIN_2_4_21_ITERABLE_MAP_V1 -> "e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296"
                KOTLIN_2_3_0_RUN_TOP_LEVEL_V1,
                KOTLIN_2_3_0_RUN_RECEIVER_V1,
                KOTLIN_2_3_0_WITH_V1,
                KOTLIN_2_3_0_APPLY_V1,
                KOTLIN_2_3_0_LET_V1,
                KOTLIN_2_3_0_ALSO_V1 -> "bfdad3872af89a51041163c03afda592baea4e394a8b1665de5b7547f4f43ce0"
            }
}
