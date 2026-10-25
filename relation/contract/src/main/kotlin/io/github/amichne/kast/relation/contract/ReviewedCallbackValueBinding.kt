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

/** The reviewed source fixes conditional element correspondence, without an execution or count assertion. */
class ReviewedIterableMapArtifact
private constructor(
    val binaryArtifact: DependencyArtifactDigest,
    val sourceArtifact: DependencyArtifactDigest,
) {
    val sourceSections: List<ReviewedMapSourceSection> = ReviewedMapSourceSection.entries
    val sourceEntry = "commonMain/generated/_Collections.kt"
    val sourceEntrySha256 = "aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c"
    val facadeSha256 = "7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878"
    val implementationSha256 = "e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296"

    companion object {
        fun admit(
            binaryArtifact: DependencyArtifactDigest,
            sourceArtifact: DependencyArtifactDigest,
        ): Refinement<ReviewedIterableMapArtifact, ReviewedCallbackValueFailure> =
            if (
                binaryArtifact.value == "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3" &&
                    sourceArtifact.value == "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"
            )
                Refinement.Refined(ReviewedIterableMapArtifact(binaryArtifact, sourceArtifact))
            else Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH)
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedIterableMapArtifact && binaryArtifact == other.binaryArtifact

    override fun hashCode(): Int = binaryArtifact.hashCode()
}

/** Reviewed spans belong to the exact source artifact and source entry. */
enum class ReviewedMapSourceSection(val firstLine: Int, val lastLine: Int) {
    MAP(REVIEWED_MAP_FIRST_LINE, REVIEWED_MAP_LAST_LINE),
    MAP_TO(REVIEWED_MAP_TO_FIRST_LINE, REVIEWED_MAP_TO_LAST_LINE),
}

/** A compiler-selected supplier and formal bind a reviewed rule on one source epoch. */
class ReviewedCallbackValueBinding
private constructor(
    val enclosing: RelationEndpoint,
    val body: RelationCallableBody.Anonymous,
    val occurrence: RelationOccurrence,
    val selected: ReviewedIterableMapCallable,
    val artifact: ReviewedIterableMapArtifact,
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

    val target
        get() = selected.target

    val position
        get() = selected.position

    companion object {
        fun admit(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            occurrence: RelationOccurrence,
            supplier: RelationCallableBody.Named,
            selected: ReviewedIterableMapCallable,
            artifact: ReviewedIterableMapArtifact,
        ): Refinement<ReviewedCallbackValueBinding, ReviewedCallbackValueFailure> {
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
            artifact == other.artifact

    override fun hashCode(): Int =
        31 *
            (31 *
                (31 * (31 * (31 * enclosing.valueIdentity.hashCode() + basis.hashCode()) + body.hashCode()) +
                    occurrence.hashCode()) + selected.hashCode()) + artifact.hashCode()
}

/** Exact declaration admission precedes dependency artifact reads. */
class ReviewedIterableMapCallable
private constructor(
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
) {
    companion object {
        fun admit(
            target: CompilerGroundedSymbolEvidence,
            position: ValueArgumentPosition,
        ): Refinement<ReviewedIterableMapCallable, ReviewedCallbackValueFailure> {
            val signature =
                target.signature as? CanonicalCompilerSignature.Function
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            val file =
                target.file as? SymbolDiscoveryFileIdentity.External
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            val receiver =
                signature.receiver as? CanonicalCompilerReceiver.Present
                    ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            if (!signature.matchesReviewedIterableMap(receiver, file))
                return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            if (position.value != 0 || signature.valueParameters.map { it.value } != listOf("(T)->R"))
                return Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH)
            return Refinement.Refined(ReviewedIterableMapCallable(target, position))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedIterableMapCallable && target == other.target && position == other.position

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

private const val SHA256_LENGTH = 64
private const val MAP_TYPE_PARAMETERS = 2
private const val REVIEWED_MAP_FIRST_LINE = 1744
private const val REVIEWED_MAP_LAST_LINE = 1746
private const val REVIEWED_MAP_TO_FIRST_LINE = 1819
private const val REVIEWED_MAP_TO_LAST_LINE = 1823
