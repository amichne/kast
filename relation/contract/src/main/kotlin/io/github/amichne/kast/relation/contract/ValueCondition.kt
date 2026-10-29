package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

enum class ValueCastKind {
    UNSAFE,
    SAFE,
}

/** A use-site fact is conditional on execution reaching that use; it is not a runtime reachability proof. */
sealed interface ValueCondition {
    val origin: ValueConditionOrigin
    val useRange: ExactDeclarationTextRange

    @ConsistentCopyVisibility
    data class StringContentNonNull
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val entryRange: ExactDeclarationTextRange,
    ) : ValueCondition {
        companion object {
            fun fromCompiler(
                source: ValueSite,
                content: ValueStringContent,
            ): Refinement<StringContentNonNull, ValueSiteFailure> =
                if (
                    content.input == ValueStringContentInput.NULLABLE_STRING &&
                        source.executionOwner.range.containsValueRange(content.templateRange) &&
                        content.inputRange.containsValueRange(source.range)
                )
                    Refinement.Refined(
                        StringContentNonNull(
                            ValueConditionOrigin.fromSite(source),
                            content.templateRange,
                            content.entryRange,
                        )
                    )
                else Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
        }
    }

    /** Normal collection completion does not assert selector invocation. */
    @ConsistentCopyVisibility
    data class ReviewedCollectionNormalResult
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val binding: ReviewedCallbackValueBinding,
    ) : ValueCondition {
        companion object {
            fun fromReviewed(
                source: ValueSite,
                binding: ReviewedCallbackValueBinding,
            ): Refinement<ReviewedCollectionNormalResult, ValueSiteFailure> =
                if (binding.admitsSortedReceiver(source) || binding.admitsFilteredReceiver(source))
                    Refinement.Refined(
                        ReviewedCollectionNormalResult(
                            ValueConditionOrigin.fromSite(source),
                            binding.occurrence.range,
                            binding,
                        )
                    )
                else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
        }
    }

    /** The mapped value belongs to the result only if its insertion survives later equal keys. */
    @ConsistentCopyVisibility
    data class ReviewedMappedKeySurvives
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val entry: ReviewedMappedEntryOrigin,
    ) : ValueCondition {
        companion object {
            fun fromReviewed(
                source: ValueSite,
                entry: ReviewedMappedEntryOrigin,
            ): Refinement<ReviewedMappedKeySurvives, ValueSiteFailure> =
                if (entry.matches(source))
                    Refinement.Refined(
                        ReviewedMappedKeySurvives(
                            ValueConditionOrigin.fromSite(source),
                            entry.binding.occurrence.range,
                            entry,
                        )
                    )
                else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
        }
    }

    /** The tracked key component survives callback effects and later equal-key insertions. */
    @ConsistentCopyVisibility
    data class ReviewedPreservedKeySurvives
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val entry: ReviewedPreservedKeyOrigin,
    ) : ValueCondition {
        companion object {
            fun fromReviewed(
                source: ValueSite,
                entry: ReviewedPreservedKeyOrigin,
            ): Refinement<ReviewedPreservedKeySurvives, ValueSiteFailure> =
                if (entry.matches(source))
                    Refinement.Refined(
                        ReviewedPreservedKeySurvives(
                            ValueConditionOrigin.fromSite(source),
                            entry.family.binding.occurrence.range,
                            entry,
                        )
                    )
                else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
        }
    }

    /** Declaration metadata does not prove stable contents or a pure getter implementation. */
    @ConsistentCopyVisibility
    data class ReviewedEntryComponentStable
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val binding: ReviewedCallValueBinding,
        val entry: ReviewedMapEntryOrigin,
        val slot: MapEntrySlot,
    ) : ValueCondition {
        companion object {
            fun fromReviewed(
                source: ValueSite,
                binding: ReviewedCallValueBinding,
            ): Refinement<ReviewedEntryComponentStable, ValueSiteFailure> =
                when (val admitted = admitReviewedEntryProjection(source, binding)) {
                    is Refinement.Refined ->
                        Refinement.Refined(
                            ReviewedEntryComponentStable(
                                ValueConditionOrigin.fromSite(source),
                                binding.invocation.range,
                                binding,
                                admitted.value.entry,
                                admitted.value.slot,
                            )
                        )
                    is Refinement.Rejected -> Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                }
        }
    }

    /** Invoked prefix components return normally and preserve the tracked slot until this read. */
    @ConsistentCopyVisibility
    data class ReviewedEntryPrefixNormal
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val binding: ReviewedCallValueBinding,
        val entry: ReviewedMapEntryOrigin,
        val slot: MapEntrySlot,
    ) : ValueCondition {
        companion object {
            fun fromReviewed(
                source: ValueSite,
                binding: ReviewedCallValueBinding,
            ): Refinement<ReviewedEntryPrefixNormal, ValueSiteFailure> {
                val order =
                    binding.entryReadOrder as? ReviewedEntryReadOrder.Destructured
                        ?: return Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                if (order.prefix !is ReviewedEntryReadPrefix.FirstComponent)
                    return Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                return when (val admitted = admitReviewedEntryProjection(source, binding)) {
                    is Refinement.Refined ->
                        Refinement.Refined(
                            ReviewedEntryPrefixNormal(
                                ValueConditionOrigin.fromSite(source),
                                binding.invocation.range,
                                binding,
                                admitted.value.entry,
                                admitted.value.slot,
                            )
                        )
                    is Refinement.Rejected -> Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                }
            }
        }
    }

    /** Exact correlated entry identity depends on a key unique across the complete insertion set. */
    @ConsistentCopyVisibility
    data class ReviewedEntryKeyUnique
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val entry: ReviewedMapEntryCorrelation,
    ) : ValueCondition {
        companion object {
            fun fromReviewed(
                source: ValueSite,
                entry: ReviewedMapEntryCorrelation,
            ): Refinement<ReviewedEntryKeyUnique, ValueSiteFailure> =
                if (entry.keyEvidence == ReviewedEntryKeyEvidence.Conditional && entry.matches(source))
                    Refinement.Refined(
                        ReviewedEntryKeyUnique(
                            ValueConditionOrigin.fromSite(source),
                            entry.binding.occurrence.range,
                            entry,
                        )
                    )
                else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
        }
    }

    /** The receiver result depends on normal callback return, without asserting that return occurs. */
    @ConsistentCopyVisibility
    data class ReviewedCallbackNormalReturn
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val binding: ReviewedCallbackValueBinding,
    ) : ValueCondition {
        companion object {
            fun fromReviewed(
                source: ValueSite,
                binding: ReviewedCallbackValueBinding,
            ): Refinement<ReviewedCallbackNormalReturn, ValueSiteFailure> =
                if (
                    binding.invocationEvidence is ReviewedCallbackInvocationEvidence.Declared &&
                        binding.admitsReceiver(source)
                )
                    Refinement.Refined(
                        ReviewedCallbackNormalReturn(
                            ValueConditionOrigin.fromSite(source),
                            binding.occurrence.range,
                            binding,
                        )
                    )
                else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
        }
    }

    /** Conditional result dependence, without asserting invocation or normal completion at runtime. */
    @ConsistentCopyVisibility
    data class DeclaredCallbackResult
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val binding: DeclaredCallbackValueBinding,
    ) : ValueCondition {
        companion object {
            fun fromCompiler(
                source: ValueSite,
                binding: DeclaredCallbackValueBinding,
            ): Refinement<DeclaredCallbackResult, ValueSiteFailure> =
                if (binding.admitsReturn(source))
                    Refinement.Refined(
                        DeclaredCallbackResult(
                            ValueConditionOrigin.fromSite(source),
                            binding.contract.occurrence.range,
                            binding,
                        )
                    )
                else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
        }
    }

    /** A stable use-site refinement belongs to one exact reaching definition, not every value of the local. */
    @ConsistentCopyVisibility
    data class StableLocal
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val version: ValueLocalVersion.Defined,
        val originalType: CanonicalCompilerType,
        val refinedType: CanonicalCompilerType,
    ) : ValueCondition {
        companion object {
            fun fromCompiler(
                source: ValueSite,
                useRange: ExactDeclarationTextRange,
                originalType: CanonicalCompilerType,
                refinedType: CanonicalCompilerType,
            ): Refinement<StableLocal, ValueSiteFailure> {
                val version = source.localVersion
                return when {
                    source.role != ValueRole.LocalBinding || version !is ValueLocalVersion.Defined ->
                        Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                    !version.admitsRead(useRange) -> Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER)
                    else ->
                        Refinement.Refined(
                            StableLocal(
                                ValueConditionOrigin.fromSite(source),
                                useRange,
                                version,
                                originalType,
                                refinedType,
                            )
                        )
                }
            }
        }
    }

    /**
     * Prerequisite that the cast completes preserving its operand. Unsafe failure is abrupt; safe failure produces
     * null, not the operand. Types describe the compiler operand and target, not a runtime generic check.
     */
    @ConsistentCopyVisibility
    data class CastSuccess
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val kind: ValueCastKind,
        val originalType: CanonicalCompilerType,
        val targetType: CanonicalCompilerType,
    ) : ValueCondition {
        companion object {
            /** Native K2 identifies the operand and entire cast, including any parenthesized operand. */
            fun fromCompiler(
                source: ValueSite,
                useRange: ExactDeclarationTextRange,
                kind: ValueCastKind,
                originalType: CanonicalCompilerType,
                targetType: CanonicalCompilerType,
            ): Refinement<CastSuccess, ValueSiteFailure> =
                when {
                    !source.executionOwner.range.containsValueRange(useRange) ->
                        Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
                    !useRange.containsValueRange(source.range) ->
                        Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER)
                    else ->
                        Refinement.Refined(
                            CastSuccess(
                                ValueConditionOrigin.fromSite(source),
                                useRange,
                                kind,
                                originalType,
                                targetType,
                            )
                        )
                }
        }
    }

    @ConsistentCopyVisibility
    data class StableReceiver
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val kind: ValueReceiverKind,
        val originalType: CanonicalCompilerType,
        val refinedType: CanonicalCompilerType,
    ) : ValueCondition {
        companion object {
            /** Native K2 establishes stability, receiver identity and both types at this exact use. */
            fun fromCompiler(
                source: ValueSite,
                useRange: ExactDeclarationTextRange,
                kind: ValueReceiverKind,
                originalType: CanonicalCompilerType,
                refinedType: CanonicalCompilerType,
            ): Refinement<StableReceiver, ValueSiteFailure> =
                when {
                    source.role != ValueRole.Formal(ValueFormalPosition.Receiver(kind)) ->
                        Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                    !source.executionOwner.range.containsValueRange(useRange) ->
                        Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
                    else ->
                        Refinement.Refined(
                            StableReceiver(
                                ValueConditionOrigin.fromSite(source),
                                useRange,
                                kind,
                                originalType,
                                refinedType,
                            )
                        )
                }
        }
    }
}

/** Ordered, immutable prerequisites. Reobserving the same fact does not grow the path state. */
