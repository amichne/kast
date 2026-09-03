package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature

enum class ProtocolOffsetFailure {
    NEGATIVE,
}

/** One non-negative source offset admitted at the public transport boundary. */
@JvmInline
value class ProtocolOffset private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<ProtocolOffset, ProtocolOffsetFailure>`.
         *
         * Establishes a non-negative source offset. [ProtocolOffsetFailure] is the closed expected
         * failure. Raw extraction is permitted only at the compiler or text-search adapter.
         */
        fun parse(raw: Int): Refinement<ProtocolOffset, ProtocolOffsetFailure> =
            if (raw < 0) {
                Refinement.Rejected(ProtocolOffsetFailure.NEGATIVE)
            } else {
                Refinement.Refined(ProtocolOffset(raw))
            }
    }
}

enum class SourceRangeDocumentFailure {
    EMPTY_OR_REVERSED,
}

/** One non-empty, half-open source range. */
@ConsistentCopyVisibility
data class SourceRangeDocument private constructor(
    val startInclusive: ProtocolOffset,
    val endExclusive: ProtocolOffset,
) {
    companion object {
        /**
         * Proof transition:
         * `(ProtocolOffset, ProtocolOffset) -> Refinement<SourceRangeDocument,
         * SourceRangeDocumentFailure>`.
         *
         * Establishes a non-empty half-open source range. The closed expected failure is
         * [SourceRangeDocumentFailure]. Raw offsets may be extracted only at a compiler, search,
         * or presentation boundary.
         */
        fun create(
            startInclusive: ProtocolOffset,
            endExclusive: ProtocolOffset,
        ): Refinement<SourceRangeDocument, SourceRangeDocumentFailure> =
            if (endExclusive.value <= startInclusive.value) {
                Refinement.Rejected(SourceRangeDocumentFailure.EMPTY_OR_REVERSED)
            } else {
                Refinement.Refined(SourceRangeDocument(startInclusive, endExclusive))
            }
    }
}

enum class SymbolNameKindDocument {
    FILE,
    CLASS,
    SYMBOL,
}

enum class SymbolDiscoveryMatchDocument {
    FUZZY,
    EXACT_NAME,
}

sealed interface SymbolTextScopeDocument {
    data object Workspace : SymbolTextScopeDocument

    data class File(
        val file: ProtocolText,
    ) : SymbolTextScopeDocument
}

/** Closed public discovery meaning carried by the existing `symbol.discover` operation. */
sealed interface SymbolDiscoverTargetDocument {
    data class Name(
        val query: ProtocolText,
        val kind: SymbolNameKindDocument,
        val match: SymbolDiscoveryMatchDocument,
    ) : SymbolDiscoverTargetDocument

    data class Location(
        val file: ProtocolText,
        val offset: ProtocolOffset,
    ) : SymbolDiscoverTargetDocument

    data class Text(
        val query: ProtocolText,
        val scope: SymbolTextScopeDocument,
    ) : SymbolDiscoverTargetDocument
}

data class SymbolDiscoverRequest(
    val target: SymbolDiscoverTargetDocument,
    val limit: ProtocolCount,
) : OperationRequest

enum class SymbolDiscoveryKindDocument {
    FILE,
    CLASS,
    SYMBOL,
}

/** Structured discovery evidence; each variant carries only facts proved for that mode. */
sealed interface SymbolDiscoveryDocument {
    data class File(
        val candidateSelector: ProtocolText,
        val name: ProtocolText,
        val file: ProtocolText,
    ) : SymbolDiscoveryDocument

    data class Declaration(
        val candidateSelector: ProtocolText,
        val kind: SymbolDiscoveryKindDocument,
        val name: ProtocolText,
        val file: ProtocolText,
        val offset: ProtocolOffset,
    ) : SymbolDiscoveryDocument

    data class TextMatch(
        val candidateSelector: ProtocolText,
        val query: ProtocolText,
        val file: ProtocolText,
        val range: SourceRangeDocument,
    ) : SymbolDiscoveryDocument
}

data class SymbolDiscoverResult(
    val items: BoundedProtocolList<SymbolDiscoveryDocument>,
) : OperationResult

enum class SymbolDiscoverLimitation {
    RESULT_LIMIT,
    BYTE_LIMIT,
    WORK_LIMIT,
    TIME_LIMIT,
    DUMB_MODE_TRANSITION,
    PROVIDER_FAILURE,
    UNSCOPED_PROVIDER,
    UNSUPPORTED_ITEM,
    EXACT_DEFINITION_UNAVAILABLE,
}

enum class SymbolDiscoverQualificationFailure {
    EMPTY,
}

/** A non-empty, deterministically ordered set of limitations attached to a qualified discovery. */
class SymbolDiscoverQualification private constructor(
    val limitations: List<SymbolDiscoverLimitation>,
) : OperationQualification {
    companion object {
        /**
         * Proof transition:
         * `Set<SymbolDiscoverLimitation> -> Refinement<SymbolDiscoverQualification,
         * SymbolDiscoverQualificationFailure>`.
         *
         * Establishes a non-empty, deterministically ordered public limitation list, so a qualified
         * discovery outcome cannot be represented without its limitations.
         * [SymbolDiscoverQualificationFailure] is the closed expected failure. Raw limitation sets
         * may be extracted only at the domain-to-protocol composition and wire boundaries.
         */
        fun from(
            raw: Set<SymbolDiscoverLimitation>,
        ): Refinement<SymbolDiscoverQualification, SymbolDiscoverQualificationFailure> {
            val canonical = raw.distinct().sorted()
            return if (canonical.isEmpty()) {
                Refinement.Rejected(SymbolDiscoverQualificationFailure.EMPTY)
            } else {
                Refinement.Refined(SymbolDiscoverQualification(canonical))
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SymbolDiscoverQualification && limitations == other.limitations

    override fun hashCode(): Int = limitations.hashCode()

    override fun toString(): String = limitations.toString()
}

enum class SymbolDiscoverRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    QUERY_REJECTED,
}

/** Closed selector authority accepted by `symbol.inspect`. */
sealed interface SymbolInspectTarget {
    /** Weaker discovery evidence that must be refined through compiler analysis. */
    data class Candidate(
        val selector: ProtocolText,
    ) : SymbolInspectTarget

    /** Already exact compiler selector that must be revalidated before projection. */
    data class Exact(
        val selector: ProtocolText,
    ) : SymbolInspectTarget
}

data class SymbolInspectRequest(
    val target: SymbolInspectTarget,
) : OperationRequest

enum class SymbolKindDocument {
    CLASSLIKE,
    CONSTRUCTOR,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

sealed interface SymbolQualifiedIdentityDocument {
    data class Available(
        val value: ProtocolText,
    ) : SymbolQualifiedIdentityDocument

    data object Unavailable : SymbolQualifiedIdentityDocument
}

enum class CompilerTypeParameterCountDocumentFailure {
    NEGATIVE,
}

/** Exact non-negative type-parameter count retained from a compiler function signature. */
@JvmInline
value class CompilerTypeParameterCountDocument private constructor(
    val value: Int,
) {
    companion object {
        fun parse(
            raw: Int,
        ): Refinement<CompilerTypeParameterCountDocument, CompilerTypeParameterCountDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(CompilerTypeParameterCountDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(CompilerTypeParameterCountDocument(raw))
            }
    }
}

/** Closed receiver state retained from compiler-grounded symbol evidence. */
sealed interface CompilerReceiverDocument {
    data object Absent : CompilerReceiverDocument

    data class Present(
        val compilerType: ProtocolText,
    ) : CompilerReceiverDocument
}

/** Structured canonical compiler signature; no signature fact is collapsed into display text. */
sealed interface CompilerSignatureDocument {
    data class Function(
        val qualifiedIdentity: ProtocolText,
        val receiver: CompilerReceiverDocument,
        val contextReceivers: BoundedProtocolList<ProtocolText>,
        val valueParameters: BoundedProtocolList<ProtocolText>,
        val typeParameterCount: CompilerTypeParameterCountDocument,
    ) : CompilerSignatureDocument

    data class Property(
        val qualifiedIdentity: ProtocolText,
        val receiver: CompilerReceiverDocument,
        val contextReceivers: BoundedProtocolList<ProtocolText>,
        val returnType: ProtocolText,
    ) : CompilerSignatureDocument

    data class TypeAlias(
        val qualifiedIdentity: ProtocolText,
    ) : CompilerSignatureDocument

    data class ClassLike(
        val qualifiedIdentity: ProtocolText,
    ) : CompilerSignatureDocument
}

enum class CompilerSymbolEvidenceDocumentFailure {
    INVALID_SIGNATURE,
    IDENTITY_MISMATCH,
}

/** Canonical signature plus the stable identity derived from its exact encoding. */
@ConsistentCopyVisibility
data class CompilerSymbolEvidenceDocument private constructor(
    val identity: ProtocolText,
    val signature: CompilerSignatureDocument,
) {
    companion object {
        /** Derives the public identity only from one canonical structured signature. */
        fun fromSignature(
            signature: CompilerSignatureDocument,
        ): Refinement<CompilerSymbolEvidenceDocument, CompilerSymbolEvidenceDocumentFailure> {
            val canonical = signature.canonicalSignature()
                ?: return Refinement.Rejected(
                    CompilerSymbolEvidenceDocumentFailure.INVALID_SIGNATURE,
                )
            val identity = when (
                val parsed = ProtocolText.parse(
                    CompilerSymbolIdentity.fromCanonicalSignature(canonical).value,
                )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    CompilerSymbolEvidenceDocumentFailure.INVALID_SIGNATURE,
                )
            }
            return Refinement.Refined(CompilerSymbolEvidenceDocument(identity, signature))
        }

        /** Restores public proof only when its claimed identity is signature-derived. */
        fun restore(
            identity: ProtocolText,
            signature: CompilerSignatureDocument,
        ): Refinement<CompilerSymbolEvidenceDocument, CompilerSymbolEvidenceDocumentFailure> =
            when (val evidence = fromSignature(signature)) {
                is Refinement.Rejected -> evidence
                is Refinement.Refined -> if (evidence.value.identity == identity) {
                    evidence
                } else {
                    Refinement.Rejected(
                        CompilerSymbolEvidenceDocumentFailure.IDENTITY_MISMATCH,
                    )
                }
            }
    }
}

enum class SymbolDocumentFailure {
    SIGNATURE_KIND_MISMATCH,
    QUALIFIED_IDENTITY_MISMATCH,
}

/** One exact, generation-bound symbol projected as structured public evidence. */
@ConsistentCopyVisibility
data class SymbolDocument private constructor(
    val selector: ProtocolText,
    val kind: SymbolKindDocument,
    val name: ProtocolText,
    val qualifiedIdentity: SymbolQualifiedIdentityDocument,
    val file: ProtocolText,
    val range: SourceRangeDocument,
    val compilerEvidence: CompilerSymbolEvidenceDocument,
) {
    companion object {
        fun create(
            selector: ProtocolText,
            kind: SymbolKindDocument,
            name: ProtocolText,
            qualifiedIdentity: SymbolQualifiedIdentityDocument,
            file: ProtocolText,
            range: SourceRangeDocument,
            compilerEvidence: CompilerSymbolEvidenceDocument,
        ): Refinement<SymbolDocument, SymbolDocumentFailure> {
            if (!compilerEvidence.signature.supports(kind)) {
                return Refinement.Rejected(SymbolDocumentFailure.SIGNATURE_KIND_MISMATCH)
            }
            if (
                qualifiedIdentity !is SymbolQualifiedIdentityDocument.Available ||
                qualifiedIdentity.value != compilerEvidence.signature.qualifiedIdentity()
            ) {
                return Refinement.Rejected(SymbolDocumentFailure.QUALIFIED_IDENTITY_MISMATCH)
            }
            return Refinement.Refined(
                SymbolDocument(
                    selector,
                    kind,
                    name,
                    qualifiedIdentity,
                    file,
                    range,
                    compilerEvidence,
                ),
            )
        }
    }
}

private fun CompilerSignatureDocument.canonicalSignature(): CanonicalCompilerSignature? {
    val canonical = when (this) {
        is CompilerSignatureDocument.Function -> CanonicalCompilerSignature.function(
            rawQualifiedIdentity = qualifiedIdentity.value,
            rawReceiverType = when (val value = receiver) {
                CompilerReceiverDocument.Absent -> null
                is CompilerReceiverDocument.Present -> value.compilerType.value
            },
            rawContextReceiverTypes = contextReceivers.values.map(ProtocolText::value),
            rawValueParameterTypes = valueParameters.values.map(ProtocolText::value),
            rawTypeParameterCount = typeParameterCount.value,
        ).valueOrNull() ?: return null
        is CompilerSignatureDocument.Property -> CanonicalCompilerSignature.property(
            rawQualifiedIdentity = qualifiedIdentity.value,
            rawReceiverType = when (val value = receiver) {
                CompilerReceiverDocument.Absent -> null
                is CompilerReceiverDocument.Present -> value.compilerType.value
            },
            rawContextReceiverTypes = contextReceivers.values.map(ProtocolText::value),
            rawReturnType = returnType.value,
        ).valueOrNull() ?: return null
        is CompilerSignatureDocument.TypeAlias -> CanonicalCompilerSignature.typeAlias(
            qualifiedIdentity.value,
        ).valueOrNull() ?: return null
        is CompilerSignatureDocument.ClassLike -> CanonicalCompilerSignature.classLike(
            qualifiedIdentity.value,
        ).valueOrNull() ?: return null
    }
    return canonical.takeIf { matchesCanonical(it) }
}

private fun CompilerSignatureDocument.matchesCanonical(
    canonical: CanonicalCompilerSignature,
): Boolean = when {
    this is CompilerSignatureDocument.Function &&
        canonical is CanonicalCompilerSignature.Function ->
        qualifiedIdentity.value == canonical.qualifiedIdentity.value &&
            receiver.matchesCanonical(canonical.receiver) &&
            contextReceivers.values.map(ProtocolText::value) ==
            canonical.contextReceivers.map { it.value } &&
            valueParameters.values.map(ProtocolText::value) ==
            canonical.valueParameters.map { it.value } &&
            typeParameterCount.value == canonical.typeParameterCount.value
    this is CompilerSignatureDocument.Property &&
        canonical is CanonicalCompilerSignature.Property ->
        qualifiedIdentity.value == canonical.qualifiedIdentity.value &&
            receiver.matchesCanonical(canonical.receiver) &&
            contextReceivers.values.map(ProtocolText::value) ==
            canonical.contextReceivers.map { it.value } &&
            returnType.value == canonical.returnType.value
    this is CompilerSignatureDocument.TypeAlias &&
        canonical is CanonicalCompilerSignature.TypeAlias ->
        qualifiedIdentity.value == canonical.qualifiedIdentity.value
    this is CompilerSignatureDocument.ClassLike &&
        canonical is CanonicalCompilerSignature.ClassLike ->
        qualifiedIdentity.value == canonical.qualifiedIdentity.value
    else -> false
}

private fun CompilerReceiverDocument.matchesCanonical(
    canonical: CanonicalCompilerReceiver,
): Boolean = when {
    this is CompilerReceiverDocument.Absent && canonical is CanonicalCompilerReceiver.Absent -> true
    this is CompilerReceiverDocument.Present && canonical is CanonicalCompilerReceiver.Present ->
        compilerType.value == canonical.type.value
    else -> false
}

internal fun CompilerSignatureDocument.supports(kind: SymbolKindDocument): Boolean = when (this) {
    is CompilerSignatureDocument.Function ->
        kind == SymbolKindDocument.FUNCTION || kind == SymbolKindDocument.CONSTRUCTOR
    is CompilerSignatureDocument.Property -> kind == SymbolKindDocument.PROPERTY
    is CompilerSignatureDocument.TypeAlias -> kind == SymbolKindDocument.TYPE_ALIAS
    is CompilerSignatureDocument.ClassLike -> kind == SymbolKindDocument.CLASSLIKE
}

internal fun CompilerSignatureDocument.qualifiedIdentity(): ProtocolText = when (this) {
    is CompilerSignatureDocument.Function -> qualifiedIdentity
    is CompilerSignatureDocument.Property -> qualifiedIdentity
    is CompilerSignatureDocument.TypeAlias -> qualifiedIdentity
    is CompilerSignatureDocument.ClassLike -> qualifiedIdentity
}

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

data class SymbolInspectResult(
    val symbol: SymbolDocument,
) : OperationResult

enum class SymbolInspectQualification : OperationQualification {
    EVIDENCE_INCOMPLETE,
}

enum class SymbolInspectRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    CANDIDATE_STALE,
    CANDIDATE_NOT_DECLARATION,
    EXACT_SELECTOR_STALE,
    AMBIGUOUS,
    NOT_FOUND,
}
