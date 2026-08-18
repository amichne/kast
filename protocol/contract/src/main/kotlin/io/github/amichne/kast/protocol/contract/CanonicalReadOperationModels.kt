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
                Refinement.Refined(BoundedProtocolList(values.toList()))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is BoundedProtocolList<*> && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "BoundedProtocolList(values=$values)"
}

data object WorkspaceInspectRequest : OperationRequest

data class WorkspaceInspectResult(
    val canonicalRoot: ProtocolText,
    val state: WorkspaceStateDocument,
) : OperationResult

enum class WorkspaceStateDocument {
    ABSENT,
    STARTING,
    RECONCILING,
    READY,
    BLOCKED,
    STOPPING,
}

enum class WorkspaceInspectQualification : OperationQualification {
    RECONCILING,
}

enum class WorkspaceInspectRejection : OperationRejection {
    ROOT_UNAVAILABLE,
    RUNTIME_BLOCKED,
}

data class SymbolDiscoverRequest(
    val query: ProtocolText,
    val limit: ProtocolCount,
) : OperationRequest

data class SymbolDiscoverResult(
    val candidateSelectors: BoundedProtocolList<ProtocolText>,
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

data class SymbolDescribeResult(
    val declaration: ProtocolText,
) : OperationResult

enum class SymbolDescribeQualification : OperationQualification {
    EVIDENCE_INCOMPLETE,
}

enum class SymbolDescribeRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    SELECTOR_STALE,
    NOT_FOUND,
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
    val targetSelectors: BoundedProtocolList<ProtocolText>,
) : OperationResult

enum class RelationReadQualification : OperationQualification {
    RESULT_LIMIT,
    COVERAGE_INCOMPLETE,
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
    val reachedSelectors: BoundedProtocolList<ProtocolText>,
) : OperationResult

enum class TraversalRunQualification : OperationQualification {
    DEPTH_LIMIT,
    RESULT_LIMIT,
    COVERAGE_INCOMPLETE,
}

enum class TraversalRunRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    SELECTOR_STALE,
    PLAN_REJECTED,
}

data class DiagnosticCheckRequest(
    val scope: ProtocolText,
    val limit: ProtocolCount,
) : OperationRequest

data class DiagnosticCheckResult(
    val diagnostics: BoundedProtocolList<ProtocolText>,
) : OperationResult

enum class DiagnosticCheckQualification : OperationQualification {
    RESULT_LIMIT,
    COVERAGE_INCOMPLETE,
}

enum class DiagnosticCheckRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    SCOPE_REJECTED,
}
