package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

private const val MAX_PROTOCOL_TEXT_LENGTH = 1_048_576
private const val MAX_PROTOCOL_ITEMS = 1_000
private const val MAX_PROTOCOL_COUNT = 1_000

enum class ProtocolTextFailure {
    BLANK,
    TOO_LONG,
}

/** One non-blank, bounded text atom admitted at the public transport boundary. */
@JvmInline
value class ProtocolText private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<ProtocolText, ProtocolTextFailure>`.
         *
         * Establishes non-blank bounded public text. [ProtocolTextFailure] is the closed expected
         * failure. Raw text may be extracted only by CLI presentation or a domain-specific
         * composition adapter.
         */
        fun parse(raw: String): Refinement<ProtocolText, ProtocolTextFailure> = when {
            raw.isBlank() -> Refinement.Rejected(ProtocolTextFailure.BLANK)
            raw.length > MAX_PROTOCOL_TEXT_LENGTH ->
                Refinement.Rejected(ProtocolTextFailure.TOO_LONG)
            else -> Refinement.Refined(ProtocolText(raw))
        }
    }
}

enum class ProtocolCountFailure {
    NOT_POSITIVE,
    TOO_LARGE,
}

/** One positive, bounded public request count. */
@JvmInline
value class ProtocolCount private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<ProtocolCount, ProtocolCountFailure>`.
         *
         * Establishes a positive count no greater than the public protocol maximum.
         * [ProtocolCountFailure] is the closed expected failure. Raw extraction is permitted only
         * at resource-budget composition.
         */
        fun parse(raw: Int): Refinement<ProtocolCount, ProtocolCountFailure> = when {
            raw < 1 -> Refinement.Rejected(ProtocolCountFailure.NOT_POSITIVE)
            raw > MAX_PROTOCOL_COUNT -> Refinement.Rejected(ProtocolCountFailure.TOO_LARGE)
            else -> Refinement.Refined(ProtocolCount(raw))
        }
    }
}

enum class ProtocolCollectionFailure {
    TOO_LARGE,
}

/** An immutable public collection proven to remain within the transport result bound. */
class BoundedProtocolList<Value> private constructor(
    val values: List<Value>,
) {
    companion object {
        /**
         * Proof transition: `List<Value> -> Refinement<BoundedProtocolList<Value>,
         * ProtocolCollectionFailure>`.
         *
         * Establishes an immutable collection containing at most 1,000 values.
         * [ProtocolCollectionFailure] is the closed expected failure. The list may be extracted
         * only by wire serialization or an operation-specific presentation boundary.
         */
        fun <Value> create(
            values: List<Value>,
        ): Refinement<BoundedProtocolList<Value>, ProtocolCollectionFailure> =
            if (values.size > MAX_PROTOCOL_ITEMS) {
                Refinement.Rejected(ProtocolCollectionFailure.TOO_LARGE)
            } else {
                Refinement.Refined(BoundedProtocolList(java.util.List.copyOf(values)))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is BoundedProtocolList<*> && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "BoundedProtocolList(values=$values)"
}

enum class RelationKindDocument {
    REFERENCES,
    CALLERS,
    CALLEES,
    IMPLEMENTATIONS,
    INHERITORS,
    OVERRIDES,
    TYPE_USES,
}

data class RelationReadRequest(
    val exactSelector: ProtocolText,
    val relation: RelationKindDocument,
    val limit: ProtocolCount,
) : OperationRequest

data class RelationReadResult(
    val relations: BoundedProtocolList<RelationFactDocument>,
) : OperationResult

enum class RelationProvenanceDocument {
    K2_AUTHORED_SOURCE,
    K2_GENERATED_SOURCE,
    K2_PROJECT_LIBRARY,
}

enum class RelationFactCoverageDocument {
    EXACT_COMPILER_CONFIRMED,
}

data class RelationOccurrenceDocument(
    val candidateSelector: ProtocolText,
    val file: ProtocolText,
    val range: SourceRangeDocument,
)

/** Exact compiler-confirmed relation fact with orientation and source occurrence intact. */
data class RelationFactDocument(
    val meaning: RelationKindDocument,
    val source: SymbolDocument,
    val target: SymbolDocument,
    val occurrence: RelationOccurrenceDocument,
    val provenance: RelationProvenanceDocument,
    val coverage: RelationFactCoverageDocument,
)

enum class RelationLimitationDocument {
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DUMB_MODE_TRANSITION,
    UNRESOLVED_TARGET,
    UNSUPPORTED_ITEM,
    PROVIDER_FAILURE,
    PROVIDER_INCOMPLETE,
}

enum class RelationKnownMinimumDocumentFailure {
    NEGATIVE,
}

@JvmInline
value class RelationKnownMinimumDocument private constructor(val value: Int) {
    companion object {
        fun parse(
            raw: Int,
        ): Refinement<RelationKnownMinimumDocument, RelationKnownMinimumDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(RelationKnownMinimumDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(RelationKnownMinimumDocument(raw))
            }
    }
}

enum class RelationContinuationDocumentFailure {
    INVALID_SHA256,
}

@JvmInline
value class RelationContinuationDocument private constructor(val value: String) {
    companion object {
        fun parse(
            raw: String,
        ): Refinement<RelationContinuationDocument, RelationContinuationDocumentFailure> =
            if (raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
                Refinement.Refined(RelationContinuationDocument(raw))
            } else {
                Refinement.Rejected(RelationContinuationDocumentFailure.INVALID_SHA256)
            }
    }
}

enum class RelationReadQualificationFailure {
    EMPTY_LIMITATIONS,
    NON_CANONICAL_LIMITATIONS,
}

/** Exact known-minimum relation coverage plus every limitation and resumable proof identity. */
@ConsistentCopyVisibility
data class RelationReadQualification private constructor(
    val knownMinimum: RelationKnownMinimumDocument,
    val limitations: List<RelationLimitationDocument>,
    val continuation: RelationContinuationDocument,
) : OperationQualification {
    companion object {
        fun create(
            knownMinimum: RelationKnownMinimumDocument,
            limitations: List<RelationLimitationDocument>,
            continuation: RelationContinuationDocument,
        ): Refinement<RelationReadQualification, RelationReadQualificationFailure> {
            if (limitations.isEmpty()) {
                return Refinement.Rejected(RelationReadQualificationFailure.EMPTY_LIMITATIONS)
            }
            if (limitations != limitations.distinct().sortedBy { it.ordinal }) {
                return Refinement.Rejected(
                    RelationReadQualificationFailure.NON_CANONICAL_LIMITATIONS,
                )
            }
            return Refinement.Refined(
                RelationReadQualification(
                    knownMinimum,
                    java.util.List.copyOf(limitations),
                    continuation,
                ),
            )
        }
    }
}

enum class RelationReadRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    SELECTOR_STALE,
    RELATION_UNSUPPORTED,
}

data class TraversalRunRequest(
    val exactSelector: ProtocolText,
    val relation: RelationKindDocument,
    val maximumDepth: ProtocolCount,
    val maximumResults: ProtocolCount,
) : OperationRequest

data class TraversalRunResult(
    /** Canonical workspace root shared by every selector and proof in [records]. */
    val snapshotRoot: ProtocolText,
    val records: BoundedProtocolList<TraversalRecordDocument>,
) : OperationResult

enum class TraversalDepthDocumentFailure {
    NEGATIVE,
}

@JvmInline
value class TraversalDepthDocument private constructor(
    val value: Int,
) {
    companion object {
        fun parse(raw: Int): Refinement<TraversalDepthDocument, TraversalDepthDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(TraversalDepthDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(TraversalDepthDocument(raw))
            }
    }
}

/** One breadth-first hop retaining its exact compiler-confirmed relation fact. */
data class TraversalRecordDocument(
    val depth: TraversalDepthDocument,
    val relation: RelationFactDocument,
)

enum class TraversalLimitationDocument {
    RECORD_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DEPTH_LIMIT_REACHED,
    FRONTIER_LIMIT_REACHED,
    ONE_HOP_INCOMPLETE,
}

enum class TraversalContinuationDocumentFailure {
    INVALID_SHA256,
}

@JvmInline
value class TraversalContinuationDocument private constructor(val value: String) {
    companion object {
        fun parse(
            raw: String,
        ): Refinement<TraversalContinuationDocument, TraversalContinuationDocumentFailure> =
            if (raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
                Refinement.Refined(TraversalContinuationDocument(raw))
            } else {
                Refinement.Rejected(TraversalContinuationDocumentFailure.INVALID_SHA256)
            }
    }
}

enum class TraversalRunQualificationFailure {
    EMPTY_LIMITATIONS,
    NON_CANONICAL_LIMITATIONS,
    NON_CANONICAL_RELATION_LIMITATIONS,
    MISSING_RELATION_LIMITATIONS,
    UNEXPECTED_RELATION_LIMITATIONS,
}

/** Every traversal and one-hop limitation plus the exact deterministic resume identity. */
@ConsistentCopyVisibility
data class TraversalRunQualification private constructor(
    val limitations: List<TraversalLimitationDocument>,
    val relationLimitations: List<RelationLimitationDocument>,
    val continuation: TraversalContinuationDocument,
) : OperationQualification {
    companion object {
        fun create(
            limitations: List<TraversalLimitationDocument>,
            relationLimitations: List<RelationLimitationDocument>,
            continuation: TraversalContinuationDocument,
        ): Refinement<TraversalRunQualification, TraversalRunQualificationFailure> {
            if (limitations.isEmpty()) {
                return Refinement.Rejected(TraversalRunQualificationFailure.EMPTY_LIMITATIONS)
            }
            if (limitations != limitations.distinct().sortedBy { it.ordinal }) {
                return Refinement.Rejected(
                    TraversalRunQualificationFailure.NON_CANONICAL_LIMITATIONS,
                )
            }
            if (relationLimitations != relationLimitations.distinct().sortedBy { it.ordinal }) {
                return Refinement.Rejected(
                    TraversalRunQualificationFailure.NON_CANONICAL_RELATION_LIMITATIONS,
                )
            }
            val oneHopIncomplete = TraversalLimitationDocument.ONE_HOP_INCOMPLETE in limitations
            if (oneHopIncomplete && relationLimitations.isEmpty()) {
                return Refinement.Rejected(
                    TraversalRunQualificationFailure.MISSING_RELATION_LIMITATIONS,
                )
            }
            if (!oneHopIncomplete && relationLimitations.isNotEmpty()) {
                return Refinement.Rejected(
                    TraversalRunQualificationFailure.UNEXPECTED_RELATION_LIMITATIONS,
                )
            }
            return Refinement.Refined(
                TraversalRunQualification(
                    java.util.List.copyOf(limitations),
                    java.util.List.copyOf(relationLimitations),
                    continuation,
                ),
            )
        }
    }
}

enum class TraversalRunRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    SELECTOR_STALE,
    TOPOLOGY_BUILD_REQUIRED,
    PLAN_REJECTED,
}

data class DiagnosticCheckRequest(
    val scope: ProtocolText,
    val limit: ProtocolCount,
) : OperationRequest

data class DiagnosticCheckResult(
    val diagnostics: BoundedProtocolList<DiagnosticDocument>,
) : OperationResult

enum class DiagnosticSeverityDocument {
    ERROR,
    WARNING,
    INFO,
}

enum class DiagnosticRangeDocumentFailure {
    END_BEFORE_START,
}

@ConsistentCopyVisibility
data class DiagnosticRangeDocument private constructor(
    val startInclusive: ProtocolOffset,
    val endExclusive: ProtocolOffset,
) {
    companion object {
        fun create(
            startInclusive: ProtocolOffset,
            endExclusive: ProtocolOffset,
        ): Refinement<DiagnosticRangeDocument, DiagnosticRangeDocumentFailure> =
            if (endExclusive.value < startInclusive.value) {
                Refinement.Rejected(DiagnosticRangeDocumentFailure.END_BEFORE_START)
            } else {
                Refinement.Refined(DiagnosticRangeDocument(startInclusive, endExclusive))
            }
    }
}

data class DiagnosticLocationDocument(
    val candidateSelector: ProtocolText,
    val file: ProtocolText,
    val range: DiagnosticRangeDocument,
)

/** Detached compiler diagnostic with every admitted field retained structurally. */
data class DiagnosticDocument(
    val severity: DiagnosticSeverityDocument,
    val code: ProtocolText,
    val message: ProtocolText,
    val location: DiagnosticLocationDocument,
)

enum class DiagnosticKnownCountDocumentFailure {
    NEGATIVE,
}

@JvmInline
value class DiagnosticKnownCountDocument private constructor(val value: Int) {
    companion object {
        fun parse(
            raw: Int,
        ): Refinement<DiagnosticKnownCountDocument, DiagnosticKnownCountDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(DiagnosticKnownCountDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(DiagnosticKnownCountDocument(raw))
            }
    }
}

enum class DiagnosticLimitationReasonDocument {
    FILE_UNAVAILABLE,
    OUTSIDE_SOURCE_CONTENT,
    INDEXING,
    PSI_UNAVAILABLE,
    UNSUPPORTED_FILE_KIND,
    UNSUPPORTED_DIAGNOSTIC,
    ANALYSIS_UNAVAILABLE,
}

data class DiagnosticLimitationDocument(
    val file: ProtocolText,
    val reason: DiagnosticLimitationReasonDocument,
)

enum class DiagnosticCheckQualificationFailure {
    COMPLETE,
    NON_CANONICAL_ANALYZED_FILES,
    NON_CANONICAL_LIMITATIONS,
    ANALYZED_LIMITED_OVERLAP,
}

/** Exact diagnostic coverage, truncation state, and every file-specific limitation. */
@ConsistentCopyVisibility
data class DiagnosticCheckQualification private constructor(
    val knownDiagnosticCount: DiagnosticKnownCountDocument,
    val resultLimitReached: Boolean,
    val analyzedFiles: List<ProtocolText>,
    val limitations: List<DiagnosticLimitationDocument>,
) : OperationQualification {
    companion object {
        fun create(
            knownDiagnosticCount: DiagnosticKnownCountDocument,
            resultLimitReached: Boolean,
            analyzedFiles: List<ProtocolText>,
            limitations: List<DiagnosticLimitationDocument>,
        ): Refinement<DiagnosticCheckQualification, DiagnosticCheckQualificationFailure> {
            if (!resultLimitReached && limitations.isEmpty()) {
                return Refinement.Rejected(DiagnosticCheckQualificationFailure.COMPLETE)
            }
            if (analyzedFiles != analyzedFiles.distinct().sortedBy(ProtocolText::value)) {
                return Refinement.Rejected(
                    DiagnosticCheckQualificationFailure.NON_CANONICAL_ANALYZED_FILES,
                )
            }
            val canonicalLimitations = limitations.distinct().sortedWith(
                compareBy<DiagnosticLimitationDocument>({ it.file.value }, { it.reason.ordinal }),
            )
            if (limitations != canonicalLimitations) {
                return Refinement.Rejected(
                    DiagnosticCheckQualificationFailure.NON_CANONICAL_LIMITATIONS,
                )
            }
            val analyzed = analyzedFiles.toSet()
            if (limitations.any { it.file in analyzed }) {
                return Refinement.Rejected(
                    DiagnosticCheckQualificationFailure.ANALYZED_LIMITED_OVERLAP,
                )
            }
            return Refinement.Refined(
                DiagnosticCheckQualification(
                    knownDiagnosticCount,
                    resultLimitReached,
                    java.util.List.copyOf(analyzedFiles),
                    java.util.List.copyOf(limitations),
                ),
            )
        }
    }
}

enum class DiagnosticCheckRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    SCOPE_REJECTED,
}
