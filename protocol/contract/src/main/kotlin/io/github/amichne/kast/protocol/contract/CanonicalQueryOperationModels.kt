package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

enum class QueryDeclarationKindDocument {
    CLASS,
    CONSTRUCTOR,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

enum class QuerySourceSetDocument { MAIN, TEST }

enum class QueryContainmentDocument { DIRECT, DESCENDANTS }

sealed interface QueryMatchDocument {
    data object All : QueryMatchDocument

    data class Name(
        val text: ProtocolText,
        val matching: SymbolDiscoveryMatchDocument,
    ) : QueryMatchDocument
}

data class QueryDirectoryScopeDocument(
    val path: ProtocolText,
    val containment: QueryContainmentDocument,
)

data class QueryPackageScopeDocument(
    val name: ProtocolText,
    val containment: QueryContainmentDocument,
)

/** Intersected source-set, directory, and semantic-package discovery domain. */
data class QueryScopeDocument(
    val sourceSets: BoundedProtocolList<QuerySourceSetDocument>,
    val directory: QueryDirectoryScopeDocument?,
    val packageName: QueryPackageScopeDocument?,
)

data class QueryDiscoveryDocument(
    val match: QueryMatchDocument,
    val scope: QueryScopeDocument,
    val declarationKinds: BoundedProtocolList<QueryDeclarationKindDocument>,
)

sealed interface QueryReferenceDocument {
    val token: ProtocolText

    data class DeclarationCandidate(
        override val token: ProtocolText,
    ) : QueryReferenceDocument

    data class ExactSymbol(
        override val token: ProtocolText,
    ) : QueryReferenceDocument
}

sealed interface QueryFromDocument {
    data class Candidates(val discovery: QueryDiscoveryDocument) : QueryFromDocument
    data class Symbols(val discovery: QueryDiscoveryDocument) : QueryFromDocument
    data class References(
        val values: BoundedProtocolList<QueryReferenceDocument>,
    ) : QueryFromDocument
}

enum class QueryVisibilityDocument { PUBLIC, PROTECTED, INTERNAL, PRIVATE, LOCAL }

sealed interface QueryPredicateDocument {
    data class Visibility(
        val values: BoundedProtocolList<QueryVisibilityDocument>,
    ) : QueryPredicateDocument
}

sealed interface QueryStepDocument {
    data object Inspect : QueryStepDocument
    data class Where(val predicate: QueryPredicateDocument) : QueryStepDocument
    data class Related(val relation: RelationKindDocument) : QueryStepDocument
    data object Distinct : QueryStepDocument
}

enum class QueryCandidateFieldDocument { NAME, LOCATION }
enum class QuerySymbolFieldDocument { NAME, LOCATION, SIGNATURE }

sealed interface QueryOutputDocument {
    data class Candidates(
        val fields: BoundedProtocolList<QueryCandidateFieldDocument>,
    ) : QueryOutputDocument

    data class Symbols(
        val fields: BoundedProtocolList<QuerySymbolFieldDocument>,
    ) : QueryOutputDocument
}

enum class QueryExecutionKindDocument { EXHAUSTIVE }
enum class QueryExecutionBudgetDocument { INTERACTIVE }

data class QueryExecutionDocument(
    val kind: QueryExecutionKindDocument,
    val budget: QueryExecutionBudgetDocument,
)

/** Closed public syntax. Semantic transition compatibility is established by plan admission. */
data class QueryRunRequest(
    val from: QueryFromDocument,
    val steps: BoundedProtocolList<QueryStepDocument>,
    val output: QueryOutputDocument,
    val execution: QueryExecutionDocument,
) : OperationRequest

data class QueryCandidateLocationDocument(
    val file: ProtocolText,
    val offset: ProtocolOffset,
)

data class QueryExactLocationDocument(
    val file: ProtocolText,
    val range: SourceRangeDocument,
)

sealed interface QueryResultItemDocument {
    val ref: QueryReferenceDocument

    data class Candidate(
        override val ref: QueryReferenceDocument.DeclarationCandidate,
        val kind: SymbolDiscoveryKindDocument,
        val name: ProtocolText?,
        val location: QueryCandidateLocationDocument?,
    ) : QueryResultItemDocument

    data class ExactSymbol(
        override val ref: QueryReferenceDocument.ExactSymbol,
        val kind: SymbolKindDocument,
        val name: ProtocolText?,
        val location: QueryExactLocationDocument?,
        val signature: CompilerSignatureDocument?,
        val connections: BoundedProtocolList<RelationFactDocument>,
    ) : QueryResultItemDocument
}

sealed interface QueryItemFailureDocument {
    data class Refinement(
        val ref: QueryReferenceDocument.DeclarationCandidate,
        val reason: QueryExactFailureDocument,
    ) : QueryItemFailureDocument

    data class ExactReference(
        val ref: QueryReferenceDocument.ExactSymbol,
        val reason: QueryExactFailureDocument,
    ) : QueryItemFailureDocument

    data class Predicate(
        val ref: QueryReferenceDocument.ExactSymbol,
        val reason: QueryPredicateFailureDocument,
    ) : QueryItemFailureDocument

    data class Relation(
        val ref: QueryReferenceDocument.ExactSymbol,
        val relation: RelationKindDocument,
        val reason: QueryRelationFailureDocument,
    ) : QueryItemFailureDocument
}

enum class QueryExactFailureDocument {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_LOCATION,
    OUTSIDE_SCOPE,
    AMBIGUOUS_DECLARATION,
    UNSUPPORTED_DECLARATION,
    COMPILER_IDENTITY_UNAVAILABLE,
    DECLARATION_MOVED_OR_CHANGED,
    COMPILER_CONTRACT_VIOLATION,
}

enum class QueryPredicateFailureDocument {
    PREDICATE_UNPROVEN,
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SOURCE_STATE_MISMATCH,
    CANDIDATE_STALE,
    SOURCE_SELECTOR_STALE,
    SOURCE_SNAPSHOT_MISMATCH,
    SOURCE_UNAVAILABLE,
    DOCUMENT_DIRTY,
    PSI_DOCUMENT_UNCOMMITTED,
    OUTSIDE_SOURCE_SCOPE,
    ANCHOR_NOT_FOUND,
    AMBIGUOUS_ANCHOR,
    REGION_NOT_APPLICABLE,
    REGION_ABSENT,
    COMPILER_ANALYSIS_UNAVAILABLE,
    CONTRACT_VIOLATION,
}

enum class QueryRelationFailureDocument {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
    CONTINUATION_CURSOR_MOVED,
    COMPILER_CONTRACT_VIOLATION,
}

data class QueryRunResult(
    val items: BoundedProtocolList<QueryResultItemDocument>,
    val failures: BoundedProtocolList<QueryItemFailureDocument>,
) : OperationResult

enum class QueryLimitationDocument {
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DISCOVERY_INCOMPLETE,
    REFINEMENT_INCOMPLETE,
    VISIBILITY_INCOMPLETE,
    RELATION_INCOMPLETE,
}

enum class QueryKnownMinimumFailure { NEGATIVE }

@JvmInline
value class QueryKnownMinimum private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<QueryKnownMinimum, QueryKnownMinimumFailure> =
            if (raw < 0) Refinement.Rejected(QueryKnownMinimumFailure.NEGATIVE)
            else Refinement.Refined(QueryKnownMinimum(raw))
    }
}

enum class QueryRunQualificationFailure { EMPTY_LIMITATIONS, NON_CANONICAL_LIMITATIONS }

class QueryRunQualification private constructor(
    val knownMinimum: QueryKnownMinimum,
    val limitations: List<QueryLimitationDocument>,
) : OperationQualification {
    companion object {
        fun create(
            knownMinimum: QueryKnownMinimum,
            limitations: List<QueryLimitationDocument>,
        ): Refinement<QueryRunQualification, QueryRunQualificationFailure> = when {
            limitations.isEmpty() ->
                Refinement.Rejected(QueryRunQualificationFailure.EMPTY_LIMITATIONS)
            limitations != limitations.distinct().sortedBy { it.ordinal } ->
                Refinement.Rejected(QueryRunQualificationFailure.NON_CANONICAL_LIMITATIONS)
            else -> Refinement.Refined(QueryRunQualification(knownMinimum, limitations.toList()))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is QueryRunQualification &&
            knownMinimum == other.knownMinimum &&
            limitations == other.limitations

    override fun hashCode(): Int = 31 * knownMinimum.hashCode() + limitations.hashCode()
}

enum class QueryElementTypeDocument { DECLARATION_CANDIDATE, EXACT_SYMBOL }

enum class QueryAdmissionCorrectionDocument {
    INSERT_INSPECT,
    REMOVE_INSPECT,
    SELECT_SYMBOL_OUTPUT,
}

enum class QuerySourceRejectionReason { UNSUPPORTED_DECLARATION_KIND }

enum class QueryReferenceRejectionReason {
    WRONG_KIND,
    MALFORMED,
    INCOMPATIBLE_WORKSPACE,
    STALE_GENERATION,
}

sealed interface QueryRunRejection : OperationRejection {
    data object WorkspaceNotReady : QueryRunRejection

    data class PlanRejected(
        val position: ProtocolOffset,
        val required: QueryElementTypeDocument,
        val actual: QueryElementTypeDocument,
        val correction: QueryAdmissionCorrectionDocument,
    ) : QueryRunRejection

    data class ReferenceRejected(
        val position: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryRunRejection

    data class SourceRejected(
        val kind: QueryDeclarationKindDocument,
        val reason: QuerySourceRejectionReason,
    ) : QueryRunRejection

    data class ExecutionRejected(val reason: QueryExecutionRejectionDocument) : QueryRunRejection
}

enum class QueryExecutionRejectionDocument {
    REQUEST_REJECTED,
    DISCOVERY_REJECTED,
    REFERENCE_STALE,
    BUDGET_REJECTED,
    INTERNAL_CONTRACT_VIOLATION,
}
