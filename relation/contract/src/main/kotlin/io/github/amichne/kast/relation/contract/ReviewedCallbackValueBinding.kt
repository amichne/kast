package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

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
    val binaryArtifact: DependencyArtifactDigest,
    val sourceArtifact: DependencyArtifactDigest,
    val rule: ReviewedLibraryRule,
) {
    val sourceSections: List<ReviewedSourceSection> =
        when (rule) {
            ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1 ->
                listOf(ReviewedSourceSection.MAP_2_3_0, ReviewedSourceSection.MAP_TO_2_3_0)
            ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
                listOf(ReviewedSourceSection.MAP, ReviewedSourceSection.MAP_TO)
            ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1 -> listOf(ReviewedSourceSection.LET)
            ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1 -> listOf(ReviewedSourceSection.ALSO)
        }
    val sourceEntry
        get() = rule.sourceEntry

    val sourceEntrySha256
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
            when (selected.operation) {
                ReviewedLibraryOperation.ITERABLE_MAP -> admit(binaryArtifact, sourceArtifact)
                ReviewedLibraryOperation.LET,
                ReviewedLibraryOperation.ALSO ->
                    if (
                        binaryArtifact.value == "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1" &&
                            sourceArtifact.value == "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"
                    )
                        Refinement.Refined(
                            ReviewedLibraryArtifact(
                                binaryArtifact,
                                sourceArtifact,
                                when (selected.operation) {
                                    ReviewedLibraryOperation.LET -> ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1
                                    ReviewedLibraryOperation.ALSO -> ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1
                                    ReviewedLibraryOperation.ITERABLE_MAP ->
                                        ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1
                                },
                            )
                        )
                    else Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH)
            }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedLibraryArtifact && binaryArtifact == other.binaryArtifact && rule == other.rule

    override fun hashCode(): Int = 31 * binaryArtifact.hashCode() + rule.hashCode()
}

/** Reviewed spans belong to the exact source artifact and source entry. */
enum class ReviewedSourceSection(val firstLine: Int, val lastLine: Int) {
    MAP_2_3_0(REVIEWED_MAP_2_3_0_FIRST_LINE, REVIEWED_MAP_2_3_0_LAST_LINE),
    MAP_TO_2_3_0(REVIEWED_MAP_TO_2_3_0_FIRST_LINE, REVIEWED_MAP_TO_2_3_0_LAST_LINE),
    MAP(REVIEWED_MAP_FIRST_LINE, REVIEWED_MAP_LAST_LINE),
    MAP_TO(REVIEWED_MAP_TO_FIRST_LINE, REVIEWED_MAP_TO_LAST_LINE),
    LET(REVIEWED_LET_FIRST_LINE, REVIEWED_LET_LAST_LINE),
    ALSO(REVIEWED_ALSO_FIRST_LINE, REVIEWED_ALSO_LAST_LINE),
}

/** A compiler-selected supplier and formal bind a reviewed rule on one source epoch. */
class ReviewedCallbackValueBinding
private constructor(
    val enclosing: RelationEndpoint,
    val body: RelationCallableBody.Anonymous,
    val occurrence: RelationOccurrence,
    val selected: ReviewedLibraryCallable,
    val artifact: ReviewedLibraryArtifact,
    val invocationEvidence: ReviewedCallbackInvocationEvidence = ReviewedCallbackInvocationEvidence.Unestablished,
) {
    val basis = enclosing.lease.identity
    val retainedBytes: Long
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
                .addBytes(
                    when (val evidence = invocationEvidence) {
                        ReviewedCallbackInvocationEvidence.Unestablished -> 0L
                        is ReviewedCallbackInvocationEvidence.Declared -> evidence.binding.retainedBytes
                    }
                )

    val target
        get() = selected.target

    val position
        get() = selected.position

    internal fun composeDeclared(
        binding: DeclaredCallbackValueBinding
    ): Refinement<ReviewedCallbackValueBinding, ValueFlowUnsupportedCause> {
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
            invocationEvidence == other.invocationEvidence

    override fun hashCode(): Int {
        var result = enclosing.valueIdentity.hashCode()
        result = 31 * result + basis.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + occurrence.hashCode()
        result = 31 * result + selected.hashCode()
        result = 31 * result + artifact.hashCode()
        return 31 * result + invocationEvidence.hashCode()
    }
}

/** Exact declaration admission precedes dependency artifact reads. */
class ReviewedLibraryCallable
private constructor(
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
    val operation: ReviewedLibraryOperation,
) {
    companion object {
        fun admit(
            target: CompilerGroundedSymbolEvidence,
            position: ValueArgumentPosition,
        ): Refinement<ReviewedLibraryCallable, ReviewedCallbackValueFailure> {
            val signature =
                target.signature as? CanonicalCompilerSignature.Function
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            val file =
                target.file as? SymbolDiscoveryFileIdentity.External
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            val receiver =
                signature.receiver as? CanonicalCompilerReceiver.Present
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            val operation =
                when {
                    signature.matchesReviewedIterableMap(receiver, file) -> ReviewedLibraryOperation.ITERABLE_MAP
                    signature.matchesReviewedScope(receiver, file, "let", 2) -> ReviewedLibraryOperation.LET
                    signature.matchesReviewedScope(receiver, file, "also", 1) -> ReviewedLibraryOperation.ALSO
                    else -> return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
                }
            val parameter =
                when (operation) {
                    ReviewedLibraryOperation.ITERABLE_MAP,
                    ReviewedLibraryOperation.LET -> "(T)->R"
                    ReviewedLibraryOperation.ALSO -> "(T)->kotlin/Unit"
                }
            if (position.value != 0 || signature.valueParameters.map { it.value } != listOf(parameter))
                return Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH)
            return Refinement.Refined(ReviewedLibraryCallable(target, position, operation))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedLibraryCallable && target == other.target && position == other.position

    override fun hashCode(): Int = 31 * target.hashCode() + position.hashCode()
}

private fun RelationOccurrence.containsReviewedCallback(
    enclosing: RelationEndpoint,
    body: RelationCallableBody.Anonymous,
): Boolean = file == enclosing.file && enclosing.range.containsValueRange(range) && range.containsValueRange(body.range)

private fun CanonicalCompilerSignature.Function.matchesReviewedIterableMap(
    receiver: CanonicalCompilerReceiver.Present,
    file: SymbolDiscoveryFileIdentity.External,
): Boolean =
    qualifiedIdentity.value == "kotlin.collections.map" &&
        receiver.type.value == "kotlin/collections/Iterable<T>" &&
        contextReceivers.isEmpty() &&
        typeParameterCount.value == MAP_TYPE_PARAMETERS &&
        file.url.value.endsWith("!/kotlin/collections/CollectionsKt.class")

private fun CanonicalCompilerSignature.Function.matchesReviewedScope(
    receiver: CanonicalCompilerReceiver.Present,
    file: SymbolDiscoveryFileIdentity.External,
    name: String,
    parameters: Int,
): Boolean =
    qualifiedIdentity.value == "kotlin.$name" &&
        receiver.type.value == "T" &&
        contextReceivers.isEmpty() &&
        typeParameterCount.value == parameters &&
        file.url.value.endsWith("!/kotlin/StandardKt.class")

private const val SHA256_LENGTH = 64
private const val MAP_TYPE_PARAMETERS = 2
private const val REVIEWED_MAP_FIRST_LINE = 1744
private const val REVIEWED_MAP_LAST_LINE = 1746
private const val REVIEWED_MAP_TO_FIRST_LINE = 1819
private const val REVIEWED_MAP_TO_LAST_LINE = 1823

private const val REVIEWED_LET_FIRST_LINE = 114
private const val REVIEWED_LET_LAST_LINE = 119
private const val REVIEWED_ALSO_FIRST_LINE = 99
private const val REVIEWED_ALSO_LAST_LINE = 105

private const val REVIEWED_MAP_2_3_0_FIRST_LINE = 1585
private const val REVIEWED_MAP_2_3_0_LAST_LINE = 1587
private const val REVIEWED_MAP_TO_2_3_0_FIRST_LINE = 1660
private const val REVIEWED_MAP_TO_2_3_0_LAST_LINE = 1664
