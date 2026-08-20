package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

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

    data class Structure(
        val file: ProtocolText,
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

data class SymbolResolveRequest(
    val candidateSelector: ProtocolText,
) : OperationRequest

data class SymbolResolveResult(
    val exactSelector: ProtocolText,
) : OperationResult

enum class SymbolResolveQualification : OperationQualification {
    EVIDENCE_INCOMPLETE,
}

enum class SymbolResolveRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    CANDIDATE_STALE,
    AMBIGUOUS,
    NOT_FOUND,
}

data class SymbolDescribeRequest(
    val exactSelector: ProtocolText,
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

/** One exact, generation-bound symbol projected as structured public evidence. */
data class SymbolDocument(
    val selector: ProtocolText,
    val kind: SymbolKindDocument,
    val name: ProtocolText,
    val qualifiedIdentity: SymbolQualifiedIdentityDocument,
    val file: ProtocolText,
    val range: SourceRangeDocument,
)

data class SymbolDescribeResult(
    val symbol: SymbolDocument,
) : OperationResult

enum class SymbolDescribeQualification : OperationQualification {
    EVIDENCE_INCOMPLETE,
}

enum class SymbolDescribeRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    SELECTOR_STALE,
    NOT_FOUND,
}
