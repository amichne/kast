@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

private const val MAX_PROTOCOL_TEXT_LENGTH = 1_048_576
const val MAX_PROTOCOL_ITEMS = 1_000
private const val MAX_PROTOCOL_COUNT = 1_000

enum class ProtocolTextFailure {
    BLANK,
    TOO_LONG,
}

/** One non-blank, bounded text atom admitted at the public transport boundary. */
@JvmInline
@Serializable(with = ProtocolTextSerializer::class)
value class ProtocolText private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<ProtocolText, ProtocolTextFailure>`.
         *
         * Establishes non-blank bounded public text. [ProtocolTextFailure] is the closed expected failure. Raw text may
         * be extracted only by CLI presentation or a domain-specific composition adapter.
         */
        fun parse(raw: String): Refinement<ProtocolText, ProtocolTextFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(ProtocolTextFailure.BLANK)
                raw.length > MAX_PROTOCOL_TEXT_LENGTH -> Refinement.Rejected(ProtocolTextFailure.TOO_LONG)
                else -> Refinement.Refined(ProtocolText(raw))
            }
    }
}

internal object ProtocolTextSerializer :
    RefiningStringSerializer<ProtocolText>(
        serialName = "io.github.amichne.kast.protocol.contract.ProtocolText",
        minimumLength = 1,
        maximumLength = MAX_PROTOCOL_TEXT_LENGTH,
        pattern = "[\\s\\S]*\\S[\\s\\S]*",
    ) {
    override fun raw(value: ProtocolText): String = value.value

    override fun refine(raw: String): Refinement<ProtocolText, *> = ProtocolText.parse(raw)
}

enum class ProtocolCountFailure {
    NOT_POSITIVE,
    TOO_LARGE,
}

/** One positive, bounded public request count. */
@JvmInline
@Serializable(with = ProtocolCountSerializer::class)
value class ProtocolCount private constructor(val value: Int) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<ProtocolCount, ProtocolCountFailure>`.
         *
         * Establishes a positive count no greater than the public protocol maximum. [ProtocolCountFailure] is the
         * closed expected failure. Raw extraction is permitted only at resource-budget composition.
         */
        fun parse(raw: Int): Refinement<ProtocolCount, ProtocolCountFailure> =
            when {
                raw < 1 -> Refinement.Rejected(ProtocolCountFailure.NOT_POSITIVE)
                raw > MAX_PROTOCOL_COUNT -> Refinement.Rejected(ProtocolCountFailure.TOO_LARGE)
                else -> Refinement.Refined(ProtocolCount(raw))
            }
    }
}

internal object ProtocolCountSerializer :
    RefiningIntSerializer<ProtocolCount>(
        serialName = "io.github.amichne.kast.protocol.contract.ProtocolCount",
        minimum = 1,
        maximum = MAX_PROTOCOL_COUNT.toLong(),
    ) {
    override fun raw(value: ProtocolCount): Int = value.value

    override fun refine(raw: Int): Refinement<ProtocolCount, *> = ProtocolCount.parse(raw)
}

enum class ProtocolCollectionFailure {
    TOO_LARGE
}

/** An immutable public collection proven to remain within the transport result bound. */
@Serializable(with = BoundedProtocolListSerializer::class)
class BoundedProtocolList<Value> private constructor(val values: List<Value>) {
    companion object {
        /**
         * Proof transition: `List<Value> -> Refinement<BoundedProtocolList<Value>, ProtocolCollectionFailure>`.
         *
         * Establishes an immutable collection containing at most 1,000 values. [ProtocolCollectionFailure] is the
         * closed expected failure. The list may be extracted only by wire serialization or an operation-specific
         * presentation boundary.
         */
        fun <Value> create(values: List<Value>): Refinement<BoundedProtocolList<Value>, ProtocolCollectionFailure> =
            if (values.size > MAX_PROTOCOL_ITEMS) {
                Refinement.Rejected(ProtocolCollectionFailure.TOO_LARGE)
            } else {
                Refinement.Refined(BoundedProtocolList(java.util.List.copyOf(values)))
            }
    }

    override fun equals(other: Any?): Boolean = other is BoundedProtocolList<*> && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "BoundedProtocolList(values=$values)"
}

class BoundedProtocolListSerializer<Value>(elementSerializer: KSerializer<Value>) :
    KSerializer<BoundedProtocolList<Value>> {
    private val delegate = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor =
        annotatedDescriptor(
            delegate.descriptor,
            ProtocolCollectionConstraint(maximumItems = MAX_PROTOCOL_ITEMS),
        )

    override fun serialize(encoder: Encoder, value: BoundedProtocolList<Value>) {
        delegate.serialize(encoder, value.values)
    }

    override fun deserialize(decoder: Decoder): BoundedProtocolList<Value> =
        when (val refinement = BoundedProtocolList.create(delegate.deserialize(decoder))) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected ->
                throw SerializationException("${descriptor.serialName} rejected ${refinement.failure}")
        }
}

@Serializable
enum class RelationKindDocument {
    @SerialName("references") REFERENCES,
    @SerialName("callers") CALLERS,
    @SerialName("callees") CALLEES,
    @SerialName("implementations") IMPLEMENTATIONS,
    @SerialName("inheritors") INHERITORS,
    @SerialName("overrides") OVERRIDES,
    @SerialName("type_uses") TYPE_USES,
}

enum class RelationProvenanceDocument {
    K2_AUTHORED_SOURCE,
    K2_GENERATED_SOURCE,
    K2_PROJECT_LIBRARY,
}

enum class RelationFactCoverageDocument {
    EXACT_COMPILER_CONFIRMED
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

@Serializable
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
    PROVIDER_STALLED,
}

/** Wire-bound cumulative committed traversal work, independent of page size. */
@Serializable
data class TraversalProgressDocument(
    val checkpointSequence: Long = 0L,
    val totalReads: Long = 0L,
    val totalEdges: Long = 0L,
    val maximumDepthReached: Int = 0,
) {
    init {
        require(
            checkpointSequence >= 0L && totalReads >= checkpointSequence && totalEdges >= 0L && maximumDepthReached >= 0
        )
        require(totalReads > 0L || (totalEdges == 0L && maximumDepthReached == 0))
    }
}

enum class TraversalDepthDocumentFailure {
    NEGATIVE
}

@JvmInline
value class TraversalDepthDocument private constructor(val value: Int) {
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

@Serializable
data class DiagnosticCheckRequest(
    val path: ProtocolText,
    val limit: ProtocolCount,
    val continuation: ProtocolText? = null,
    val executionBudget: ExecutionBudgetDocument? = null,
) : OperationRequest

data class DiagnosticCheckResult(
    val diagnostics: BoundedProtocolList<DiagnosticDocument>,
    val progress: DiagnosticProgressDocument? = null,
) : OperationResult

enum class DiagnosticSeverityDocument {
    ERROR,
    WARNING,
    INFO,
}

enum class DiagnosticRangeDocumentFailure {
    END_BEFORE_START
}

@ConsistentCopyVisibility
data class DiagnosticRangeDocument
private constructor(
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
    NEGATIVE
}

@JvmInline
@Serializable(with = DiagnosticKnownCountDocumentSerializer::class)
value class DiagnosticKnownCountDocument private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<DiagnosticKnownCountDocument, DiagnosticKnownCountDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(DiagnosticKnownCountDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(DiagnosticKnownCountDocument(raw))
            }
    }
}

internal object DiagnosticKnownCountDocumentSerializer :
    RefiningIntSerializer<DiagnosticKnownCountDocument>(
        serialName = "io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument",
        minimum = 0,
        maximum = Int.MAX_VALUE.toLong(),
    ) {
    override fun raw(value: DiagnosticKnownCountDocument): Int = value.value

    override fun refine(raw: Int): Refinement<DiagnosticKnownCountDocument, *> = DiagnosticKnownCountDocument.parse(raw)
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
data class DiagnosticCheckQualification
private constructor(
    val knownDiagnosticCount: DiagnosticKnownCountDocument,
    val resultLimitReached: Boolean,
    val analyzedFiles: List<ProtocolText>,
    val limitations: List<DiagnosticLimitationDocument>,
    val continuation: ProtocolText? = null,
) : OperationQualification {
    companion object {
        fun create(
            knownDiagnosticCount: DiagnosticKnownCountDocument,
            resultLimitReached: Boolean,
            analyzedFiles: List<ProtocolText>,
            limitations: List<DiagnosticLimitationDocument>,
            continuation: ProtocolText? = null,
        ): Refinement<DiagnosticCheckQualification, DiagnosticCheckQualificationFailure> {
            if (!resultLimitReached && limitations.isEmpty() && continuation == null) {
                return Refinement.Rejected(DiagnosticCheckQualificationFailure.COMPLETE)
            }
            if (analyzedFiles != analyzedFiles.distinct().sortedBy(ProtocolText::value)) {
                return Refinement.Rejected(DiagnosticCheckQualificationFailure.NON_CANONICAL_ANALYZED_FILES)
            }
            val canonicalLimitations =
                limitations
                    .distinct()
                    .sortedWith(compareBy<DiagnosticLimitationDocument>({ it.file.value }, { it.reason.ordinal }))
            if (limitations != canonicalLimitations) {
                return Refinement.Rejected(DiagnosticCheckQualificationFailure.NON_CANONICAL_LIMITATIONS)
            }
            val analyzed = analyzedFiles.toSet()
            if (limitations.any { it.file in analyzed }) {
                return Refinement.Rejected(DiagnosticCheckQualificationFailure.ANALYZED_LIMITED_OVERLAP)
            }
            return Refinement.Refined(
                DiagnosticCheckQualification(
                    knownDiagnosticCount,
                    resultLimitReached,
                    java.util.List.copyOf(analyzedFiles),
                    java.util.List.copyOf(limitations),
                    continuation,
                )
            )
        }
    }
}

enum class DiagnosticCheckRejection : DiagnosticCheckFailure {
    ENUMERATION_INDEX_MODE_UNSUPPORTED,
    EXECUTION_TIME_GRANT_TOO_SMALL,
    CONTINUATION_UNAVAILABLE,
    CONTINUATION_REQUEST_MISMATCH,
    STALE_CONTINUATION,
    CONTINUATION_CAPACITY_EXCEEDED,
    ENUMERATION_WORK_GRANT_TOO_SMALL,
    ENUMERATION_TIME_GRANT_TOO_SMALL,
    ENUMERATION_RETENTION_EXCEEDED,
    COMPILER_UNIT_GRANT_TOO_SMALL,
    COMPILER_CONTRACT_VIOLATION,
    WORKSPACE_INDEX_UNAVAILABLE,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    OUTPUT_GRANT_TOO_SMALL,
    WORKSPACE_NOT_READY,
    SCOPE_REJECTED,
    SCOPE_EMPTY,
    SCOPE_LIMIT_EXCEEDED,
    SCOPE_UNAVAILABLE,
}
