@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class QueryDeclarationKindDocument {
    @SerialName("class")
    CLASS,
    @SerialName("constructor")
    CONSTRUCTOR,
    @SerialName("function")
    FUNCTION,
    @SerialName("property")
    PROPERTY,
    @SerialName("type-alias")
    TYPE_ALIAS,
}

@Serializable
enum class QueryContainmentDocument {
    @SerialName("direct")
    DIRECT,
    @SerialName("descendants")
    DESCENDANTS,
}

@Serializable
sealed interface QueryMatchDocument {
    @Serializable
    @SerialName("all")
    data object All : QueryMatchDocument

    @Serializable
    @SerialName("name")
    data class Name(
        @ProtocolStringConstraint(maximumLength = 256)
        val text: ProtocolText,
        val matching: SymbolDiscoveryMatchDocument,
    ) : QueryMatchDocument
}

@Serializable
data class QueryDirectoryScopeDocument(
    @ProtocolStringConstraint(
        pattern = "^(?:\\.|(?!/)(?!.*(?:^|/)(?:\\.|\\.\\.)(?:/|$))(?!.*//)[^\\x00-\\x1F\\x7F]+)$",
    )
    val path: ProtocolText,
    val containment: QueryContainmentDocument,
)

@Serializable
data class QueryPackageScopeDocument(
    @ProtocolStringConstraint(pattern = "^(?!.*\\.(?:[0-9.]|$))[A-Za-z_][A-Za-z0-9_.]*$")
    val name: ProtocolText,
    val containment: QueryContainmentDocument,
)

/** Intersected source-set, directory, and semantic-package discovery domain. */
@Serializable
data class QueryScopeDocument(
    @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
    val sourceSets: BoundedProtocolList<ProtocolText>,
    val directory: QueryDirectoryScopeDocument?,
    val packageName: QueryPackageScopeDocument?,
)

@Serializable
data class QueryDiscoveryDocument(
    val match: QueryMatchDocument,
    val scope: QueryScopeDocument,
    @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
    @ProtocolAllowedValues("class", "function", "property", "type-alias")
    val declarationKinds: BoundedProtocolList<QueryDeclarationKindDocument>,
)

@Serializable
@JsonClassDiscriminator("kind")
sealed interface QueryReferenceDocument {
    val token: ProtocolText

    @Serializable
    @SerialName("declaration-candidate")
    data class DeclarationCandidate(
        override val token: ProtocolText,
    ) : QueryReferenceDocument

    @Serializable
    @SerialName("exact-symbol")
    data class ExactSymbol(
        override val token: ProtocolText,
    ) : QueryReferenceDocument
}

@Serializable
sealed interface QueryFromDocument {
    @Serializable
    @SerialName("candidates")
    data class Candidates(
        val match: QueryMatchDocument,
        val scope: QueryScopeDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        @ProtocolAllowedValues("class", "function", "property", "type-alias")
        val declarationKinds: BoundedProtocolList<QueryDeclarationKindDocument>,
    ) : QueryFromDocument {
        constructor(discovery: QueryDiscoveryDocument) : this(
            discovery.match,
            discovery.scope,
            discovery.declarationKinds,
        )

        val discovery: QueryDiscoveryDocument
            get() = QueryDiscoveryDocument(match, scope, declarationKinds)
    }

    @Serializable
    @SerialName("symbols")
    data class Symbols(
        val match: QueryMatchDocument,
        val scope: QueryScopeDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        @ProtocolAllowedValues("class", "function", "property", "type-alias")
        val declarationKinds: BoundedProtocolList<QueryDeclarationKindDocument>,
    ) : QueryFromDocument {
        constructor(discovery: QueryDiscoveryDocument) : this(
            discovery.match,
            discovery.scope,
            discovery.declarationKinds,
        )

        val discovery: QueryDiscoveryDocument
            get() = QueryDiscoveryDocument(match, scope, declarationKinds)
    }

    @Serializable
    @SerialName("references")
    data class References(
        @ProtocolCollectionConstraint(minimumItems = 1)
        @ProtocolHomogeneousCollection
        val values: BoundedProtocolList<QueryReferenceDocument>,
    ) : QueryFromDocument
}

@Serializable
enum class QueryVisibilityDocument {
    @SerialName("public")
    PUBLIC,
    @SerialName("protected")
    PROTECTED,
    @SerialName("internal")
    INTERNAL,
    @SerialName("private")
    PRIVATE,
    @SerialName("local")
    LOCAL,
}

@Serializable
sealed interface QueryPredicateDocument {
    @Serializable
    @SerialName("visibility")
    data class Visibility(
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val values: BoundedProtocolList<QueryVisibilityDocument>,
    ) : QueryPredicateDocument
}

@Serializable
sealed interface QueryStepDocument {
    @Serializable
    @SerialName("inspect")
    data object Inspect : QueryStepDocument
    @Serializable
    @SerialName("where")
    data class Where(val predicate: QueryPredicateDocument) : QueryStepDocument
    @Serializable
    @SerialName("related")
    data class Related(val relation: RelationKindDocument) : QueryStepDocument
    @Serializable
    @SerialName("distinct")
    data object Distinct : QueryStepDocument
}

@Serializable
enum class QueryCandidateFieldDocument {
    @SerialName("name")
    NAME,
    @SerialName("location")
    LOCATION,
}

@Serializable
enum class QuerySymbolFieldDocument {
    @SerialName("name")
    NAME,
    @SerialName("location")
    LOCATION,
    @SerialName("signature")
    SIGNATURE,
}

@Serializable
sealed interface QueryOutputDocument {
    @Serializable
    @SerialName("candidates")
    data class Candidates(
        @ProtocolCollectionConstraint(uniqueItems = true)
        val fields: BoundedProtocolList<QueryCandidateFieldDocument>,
    ) : QueryOutputDocument

    @Serializable
    @SerialName("symbols")
    data class Symbols(
        @ProtocolCollectionConstraint(uniqueItems = true)
        val fields: BoundedProtocolList<QuerySymbolFieldDocument>,
    ) : QueryOutputDocument
}

@Serializable
enum class QueryExecutionKindDocument {
    @SerialName("exhaustive")
    EXHAUSTIVE,
}

@Serializable
enum class QueryExecutionBudgetDocument {
    @SerialName("interactive")
    INTERACTIVE,
}

@Serializable
data class QueryExecutionDocument(
    val kind: QueryExecutionKindDocument,
    val budget: QueryExecutionBudgetDocument,
)

/** Closed public syntax. Semantic transition compatibility is established by plan admission. */
@Serializable(with = QueryRunRequestSerializer::class)
@KeepGeneratedSerializer
data class QueryRunRequest(
    val from: QueryFromDocument,
    val steps: BoundedProtocolList<QueryStepDocument>,
    val output: QueryOutputDocument,
    val execution: QueryExecutionDocument,
) : OperationRequest

internal object QueryRunRequestSerializer : KSerializer<QueryRunRequest> {
    private val delegate = QueryRunRequest.generatedSerializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryRunRequest) {
        delegate.serialize(encoder, value.requireCanonicalSyntax())
    }

    override fun deserialize(decoder: Decoder): QueryRunRequest =
        delegate.deserialize(decoder).requireCanonicalSyntax()
}

private fun QueryRunRequest.requireCanonicalSyntax(): QueryRunRequest =
    if (hasCanonicalRequestSyntax()) this
    else throw SerializationException("QueryRunRequest rejected non-canonical query syntax")

private fun QueryRunRequest.hasCanonicalRequestSyntax(): Boolean {
    val sourceIsCanonical = when (val source = from) {
        is QueryFromDocument.Candidates -> source.discovery.isCanonical()
        is QueryFromDocument.Symbols -> source.discovery.isCanonical()
        is QueryFromDocument.References -> source.values.values.isNotEmpty() &&
            source.values.values.map { it::class }.distinct().size == 1
    }
    if (!sourceIsCanonical) return false
    if (steps.values.any { !it.hasCanonicalSyntax() }) return false
    return when (val projection = output) {
        is QueryOutputDocument.Candidates -> projection.fields.values.isUnique()
        is QueryOutputDocument.Symbols -> projection.fields.values.isUnique()
    }
}

private fun QueryDiscoveryDocument.isCanonical(): Boolean {
    if (!scope.sourceSets.values.isUniqueNonEmpty() ||
        !declarationKinds.values.isUniqueNonEmpty()
    ) return false
    if (QueryDeclarationKindDocument.CONSTRUCTOR in declarationKinds.values) return false
    val name = match as? QueryMatchDocument.Name
    if (name != null && name.text.value.length > 256) return false
    val packageName = scope.packageName?.name?.value
    if (packageName != null && !QUERY_PACKAGE_NAME.matches(packageName)) return false
    val directory = scope.directory?.path?.value
    if (directory != null && directory != "." &&
        (directory.startsWith('/') || directory.any(Char::isISOControl) ||
            directory.split('/').any { it.isBlank() || it == "." || it == ".." })
    ) {
        return false
    }
    return true
}

private fun QueryStepDocument.hasCanonicalSyntax(): Boolean = when (this) {
    QueryStepDocument.Inspect,
    is QueryStepDocument.Related,
    QueryStepDocument.Distinct,
        -> true
    is QueryStepDocument.Where -> when (val value = predicate) {
        is QueryPredicateDocument.Visibility -> value.values.values.isUniqueNonEmpty()
    }
}

private fun <Value> List<Value>.isUniqueNonEmpty(): Boolean =
    isNotEmpty() && isUnique()

private fun <Value> List<Value>.isUnique(): Boolean = size == distinct().size

private val QUERY_PACKAGE_NAME = Regex(
    "(?!.*\\.(?:[0-9.]|$))[A-Za-z_][A-Za-z0-9_.]*",
)

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
    STALE_AUTHORITY,
    INCOMPATIBLE_AUTHORITY,
    INCOMPATIBLE_REFERENCE_VERSION,
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
