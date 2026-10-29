package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

enum class ReviewedCallbackValueFailure {
    ARTIFACT_UNAVAILABLE,
    ARTIFACT_CHANGED,
    ARTIFACT_MISMATCH,
    CALLABLE_MISMATCH,
    FORMAL_MISMATCH,
    OWNER_MISMATCH,
    BASIS_MISMATCH,
    RETURN_MISMATCH,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
}

/** Digests identify complete dependency artifacts, independently of individual class content. */
@JvmInline
value class DependencyArtifactDigest private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<DependencyArtifactDigest, ReviewedCallbackValueFailure> =
            if (raw.length == SHA256_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' })
                Refinement.Refined(DependencyArtifactDigest(raw))
            else Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH)
    }
}

/** The reviewed source fixes conditional value correspondence, without an execution or count assertion. */
class ReviewedLibraryArtifact
private constructor(
    override val binaryArtifact: DependencyArtifactDigest,
    override val sourceArtifact: DependencyArtifactDigest,
    override val rule: ReviewedLibraryRule,
) : ReviewedArtifactEvidence {
    override val sourceSections: List<ReviewedSourceSection> =
        when (rule) {
            ReviewedLibraryRule.KOTLIN_2_3_0_RUN_TOP_LEVEL_V1 -> listOf(ReviewedSourceSection.RUN_TOP_LEVEL)
            ReviewedLibraryRule.KOTLIN_2_3_0_RUN_RECEIVER_V1 -> listOf(ReviewedSourceSection.RUN_RECEIVER)
            ReviewedLibraryRule.KOTLIN_2_3_0_WITH_V1 -> listOf(ReviewedSourceSection.WITH)
            ReviewedLibraryRule.KOTLIN_2_3_0_APPLY_V1 -> listOf(ReviewedSourceSection.APPLY)
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1 ->
                listOf(ReviewedSourceSection.GROUP_BY, ReviewedSourceSection.GROUP_BY_TO)
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1 ->
                listOf(ReviewedSourceSection.GROUP_BY_TRANSFORM, ReviewedSourceSection.GROUP_BY_TRANSFORM_TO)
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_FILTER_V1 ->
                listOf(ReviewedSourceSection.FILTER, ReviewedSourceSection.FILTER_TO)
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1 ->
                listOf(
                    ReviewedSourceSection.MAP_NOT_NULL,
                    ReviewedSourceSection.MAP_NOT_NULL_TO,
                    ReviewedSourceSection.FOR_EACH,
                )
            ReviewedLibraryRule.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1 ->
                listOf(ReviewedSourceSection.MAP_ENTRY_COMPONENT1)
            ReviewedLibraryRule.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1 ->
                listOf(ReviewedSourceSection.MAP_ENTRY_COMPONENT2)
            ReviewedLibraryRule.KOTLIN_2_3_0_MAP_VALUES_V1 ->
                listOf(ReviewedSourceSection.MAP_VALUES, ReviewedSourceSection.MAP_VALUES_TO)
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 ->
                listOf(
                    ReviewedSourceSection.SORTED_BY,
                    ReviewedSourceSection.SORTED_WITH,
                    ReviewedSourceSection.TO_LIST,
                    ReviewedSourceSection.TO_MUTABLE_LIST,
                    ReviewedSourceSection.COLLECTION_TO_MUTABLE_LIST,
                )
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 ->
                listOf(ReviewedSourceSection.ASSOCIATE, ReviewedSourceSection.ASSOCIATE_TO)
            ReviewedLibraryRule.KOTLIN_2_3_0_TO_V1 -> listOf(ReviewedSourceSection.TO, ReviewedSourceSection.PAIR)
            ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
            ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_FIRST_V1,
            ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_SECOND_V1 -> listOf(ReviewedSourceSection.PAIR)
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1 ->
                listOf(ReviewedSourceSection.MAP_2_3_0, ReviewedSourceSection.MAP_TO_2_3_0)
            ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
                listOf(ReviewedSourceSection.MAP, ReviewedSourceSection.MAP_TO)
            ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1 -> listOf(ReviewedSourceSection.LET)
            ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1 -> listOf(ReviewedSourceSection.ALSO)
        }
    override val helpers: List<ReviewedLibraryHelper> = rule.reviewedHelpers()

    override val sourceEntry
        get() = rule.sourceEntry

    override val sourceEntrySha256
        get() = rule.sourceEntrySha256

    val facadeSha256
        get() = rule.facadeSha256

    val implementationSha256
        get() = rule.implementationSha256

    companion object {
        fun admit(
            binaryArtifact: DependencyArtifactDigest,
            sourceArtifact: DependencyArtifactDigest,
        ): Refinement<ReviewedLibraryArtifact, ReviewedCallbackValueFailure> =
            if (
                binaryArtifact.value == "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3" &&
                    sourceArtifact.value == "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"
            )
                Refinement.Refined(
                    ReviewedLibraryArtifact(
                        binaryArtifact,
                        sourceArtifact,
                        ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1,
                    )
                )
            else if (
                binaryArtifact.value == "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1" &&
                    sourceArtifact.value == "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"
            )
                Refinement.Refined(
                    ReviewedLibraryArtifact(
                        binaryArtifact,
                        sourceArtifact,
                        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1,
                    )
                )
            else Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH)

        fun admit(
            selected: ReviewedLibraryCallable,
            binaryArtifact: DependencyArtifactDigest,
            sourceArtifact: DependencyArtifactDigest,
        ): Refinement<ReviewedLibraryArtifact, ReviewedCallbackValueFailure> =
            if (selected.operation == ReviewedLibraryOperation.ITERABLE_MAP) admit(binaryArtifact, sourceArtifact)
            else admit230(selected.operation, binaryArtifact, sourceArtifact)

        fun admit(
            selected: ReviewedOrdinaryLibraryCallable,
            binaryArtifact: DependencyArtifactDigest,
            sourceArtifact: DependencyArtifactDigest,
        ): Refinement<ReviewedArtifactEvidence, ReviewedCallbackValueFailure> =
            when (selected.operation) {
                ReviewedLibraryOperation.MAP_ENTRY_KEY,
                ReviewedLibraryOperation.MAP_ENTRY_VALUE ->
                    ReviewedEntryDeclarationArtifact.admit(selected, binaryArtifact, sourceArtifact)
                else -> admit230(selected.operation, binaryArtifact, sourceArtifact)
            }

        private fun admit230(
            operation: ReviewedLibraryOperation,
            binaryArtifact: DependencyArtifactDigest,
            sourceArtifact: DependencyArtifactDigest,
        ): Refinement<ReviewedLibraryArtifact, ReviewedCallbackValueFailure> {
            if (
                binaryArtifact.value != "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1" ||
                    sourceArtifact.value != "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"
            )
                return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH)
            val rule =
                ReviewedLibraryRule.entries.single {
                    it.operation == operation && it != ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1
                }
            return Refinement.Refined(ReviewedLibraryArtifact(binaryArtifact, sourceArtifact, rule))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedLibraryArtifact && binaryArtifact == other.binaryArtifact && rule == other.rule

    override fun hashCode(): Int = 31 * binaryArtifact.hashCode() + rule.hashCode()
}

/** A compiler-selected supplier and formal bind a reviewed rule on one source epoch. */
class ReviewedCallbackValueBinding
private constructor(
    override val enclosing: RelationEndpoint,
    val body: RelationCallableBody.Anonymous,
    override val occurrence: RelationOccurrence,
    val selected: ReviewedLibraryCallable,
    override val artifact: ReviewedLibraryArtifact,
    val invocationEvidence: ReviewedCallbackInvocationEvidence = ReviewedCallbackInvocationEvidence.Unestablished,
    val parent: ReviewedCallbackParent = ReviewedCallbackParent.Declaration,
) : ReviewedValueAuthority {
    override val basis = enclosing.lease.identity
    override val retainedBytes: Long
        get() =
            8192L
                .addBytes(enclosing.detachedTextUnits().multiplyBytes(2))
                .addBytes(body.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2))
                .addBytes(body.file.stableValue.length.toLong().multiplyBytes(2))
                .addBytes(body.compilerIdentity.value.length.toLong().multiplyBytes(2))
                .addBytes(target.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2))
                .addBytes(target.file.stableValue.length.toLong().multiplyBytes(2))
                .addBytes(target.name.value.length.toLong().multiplyBytes(2))
                .addBytes(target.compilerIdentity.value.length.toLong().multiplyBytes(2))
                .addBytes(parent.retainedBytes)
                .addBytes(
                    when (val evidence = invocationEvidence) {
                        ReviewedCallbackInvocationEvidence.Unestablished -> 0L
                        is ReviewedCallbackInvocationEvidence.Declared -> evidence.binding.retainedBytes
                    }
                )

    val parentExecutionOwner: ValueExecutionOwner
        get() =
            when (val execution = parent) {
                ReviewedCallbackParent.Declaration -> ValueExecutionOwner.declaration(enclosing)
                is ReviewedCallbackParent.DeclaredCallback -> ValueExecutionOwner.declaredCallback(execution.binding)
            }

    override val target
        get() = selected.target

    val position
        get() = selected.position

    internal fun composeDeclared(
        binding: DeclaredCallbackValueBinding
    ): Refinement<ReviewedCallbackValueBinding, ValueFlowUnsupportedCause> {
        if (parent != ReviewedCallbackParent.Declaration)
            return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        if (
            binding.basis != basis || binding.body != body || binding.enclosing.valueIdentity != enclosing.valueIdentity
        )
            return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        if (
            binding.contract.occurrence != occurrence ||
                binding.contract.target != target ||
                binding.contract.position != position
        )
            return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        if (binding.contract.classDigest.value != artifact.facadeSha256)
            return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        return Refinement.Refined(
            ReviewedCallbackValueBinding(
                enclosing,
                body,
                occurrence,
                selected,
                artifact,
                ReviewedCallbackInvocationEvidence.Declared(binding),
                parent,
            )
        )
    }

    companion object {
        fun admit(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            occurrence: RelationOccurrence,
            supplier: RelationCallableBody.Named,
            selected: ReviewedLibraryCallable,
            artifact: ReviewedLibraryArtifact,
        ): Refinement<ReviewedCallbackValueBinding, ReviewedCallbackValueFailure> {
            if (selected.operation != artifact.rule.operation)
                return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            if (
                body.file != enclosing.file ||
                    !enclosing.matchesCallbackRoot(supplier.evidence) ||
                    !occurrence.containsReviewedCallback(enclosing, body)
            )
                return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
            return Refinement.Refined(ReviewedCallbackValueBinding(enclosing, body, occurrence, selected, artifact))
        }

        fun admit(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            occurrence: RelationOccurrence,
            supplier: RelationCallableBody.Anonymous,
            parent: DeclaredCallbackValueBinding,
            selected: ReviewedLibraryCallable,
            artifact: ReviewedLibraryArtifact,
        ): Refinement<ReviewedCallbackValueBinding, ReviewedCallbackValueFailure> {
            if (parent.basis != enclosing.lease.identity)
                return Refinement.Rejected(ReviewedCallbackValueFailure.BASIS_MISMATCH)
            if (selected.operation != artifact.rule.operation)
                return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            if (supplier != parent.body || parent.enclosing.valueIdentity != enclosing.valueIdentity)
                return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
            if (
                !supplier.range.containsValueRange(occurrence.range) ||
                    body.file != enclosing.file ||
                    !occurrence.containsReviewedCallback(enclosing, body)
            )
                return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
            return Refinement.Refined(
                ReviewedCallbackValueBinding(
                    enclosing = enclosing,
                    body = body,
                    occurrence = occurrence,
                    selected = selected,
                    artifact = artifact,
                    parent = ReviewedCallbackParent.DeclaredCallback(parent),
                )
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedCallbackValueBinding &&
            enclosing.valueIdentity == other.enclosing.valueIdentity &&
            basis == other.basis &&
            body == other.body &&
            occurrence == other.occurrence &&
            target == other.target &&
            position == other.position &&
            artifact == other.artifact &&
            invocationEvidence == other.invocationEvidence &&
            parent == other.parent

    override fun hashCode(): Int {
        var result = enclosing.valueIdentity.hashCode()
        result = 31 * result + basis.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + occurrence.hashCode()
        result = 31 * result + selected.hashCode()
        result = 31 * result + artifact.hashCode()
        result = 31 * result + invocationEvidence.hashCode()
        return 31 * result + parent.hashCode()
    }
}

private fun RelationOccurrence.containsReviewedCallback(
    enclosing: RelationEndpoint,
    body: RelationCallableBody.Anonymous,
): Boolean = file == enclosing.file && enclosing.range.containsValueRange(range) && range.containsValueRange(body.range)

private const val SHA256_LENGTH = 64
