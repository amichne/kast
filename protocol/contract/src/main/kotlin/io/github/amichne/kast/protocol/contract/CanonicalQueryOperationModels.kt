@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

private const val MAX_QUERY_PRIMITIVE_VALUE_LENGTH = 512

@Serializable
enum class QueryDeclarationKindDocument {
    @SerialName("class") CLASS,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}

@Serializable
enum class QueryContainmentDocument {
    @SerialName("direct") DIRECT,
    @SerialName("descendants") DESCENDANTS,
}

@Serializable
sealed interface QueryMatchDocument {
    @Serializable @SerialName("all") data object All : QueryMatchDocument

    @Serializable
    @SerialName("name")
    data class Name(
        @ProtocolStringConstraint(maximumLength = 256) val text: ProtocolText,
        val matching: SymbolDiscoveryMatchDocument,
    ) : QueryMatchDocument
}

@Serializable
data class QueryDirectoryScopeDocument(
    @ProtocolStringConstraint(pattern = "^(?:\\.|(?!/)(?!.*(?:^|/)(?:\\.|\\.\\.)(?:/|$))(?!.*//)[^\\x00-\\x1F\\x7F]+)$")
    val path: ProtocolText,
    val containment: QueryContainmentDocument,
)

@Serializable
data class QueryPackageScopeDocument(
    @ProtocolStringConstraint(pattern = "^(?!.*\\.(?:[0-9.]|$))[A-Za-z_][A-Za-z0-9_.]*$") val name: ProtocolText,
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
    data class DeclarationCandidate(override val token: ProtocolText) : QueryReferenceDocument

    @Serializable
    @SerialName("exact-symbol")
    data class ExactSymbol(override val token: ProtocolText) : QueryReferenceDocument
}

/** Inputs that can be composed after a query has started. Discovery is a source only. */
@Serializable sealed interface QueryCompositionInputDocument

@Serializable
sealed interface QueryFromDocument {
    @Serializable
    @SerialName("symbols")
    data class Symbols(
        val match: QueryMatchDocument,
        val scope: QueryScopeDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        @ProtocolAllowedValues("class", "function", "property", "type-alias")
        val declarationKinds: BoundedProtocolList<QueryDeclarationKindDocument>,
    ) : QueryFromDocument {
        constructor(
            discovery: QueryDiscoveryDocument
        ) : this(
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
        val values: BoundedProtocolList<QueryReferenceDocument.ExactSymbol>
    ) : QueryFromDocument, QueryCompositionInputDocument

    @Serializable
    @SerialName("result")
    data class Result(
        val reference: QueryResultReference,
        @ProtocolCollectionConstraint(uniqueItems = true)
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_ids")
        val rowIds: BoundedProtocolList<QueryResultRowReference>? = null,
    ) : QueryFromDocument, QueryCompositionInputDocument
}

@Serializable
enum class QuerySymbolFieldDocument {
    @SerialName("name") NAME,
    @SerialName("location") LOCATION,
    @SerialName("signature") SIGNATURE,
    @SerialName("source") SOURCE,
}

@Serializable
sealed interface QueryOutputDocument {
    @Serializable
    @SerialName("symbols")
    data class Symbols(
        @ProtocolCollectionConstraint(uniqueItems = true) val fields: BoundedProtocolList<QuerySymbolFieldDocument>
    ) : QueryOutputDocument

    @Serializable @SerialName("occurrences") data object Occurrences : QueryOutputDocument

    @Serializable @SerialName("traversal_records") data object TraversalRecords : QueryOutputDocument

    @Serializable @SerialName("binding_rows") data object BindingRows : QueryOutputDocument
}

@Serializable
enum class QueryExecutionKindDocument {
    @SerialName("exhaustive") EXHAUSTIVE
}

@Serializable
enum class QueryExecutionBudgetDocument {
    @SerialName("interactive") INTERACTIVE
}

@Serializable
data class QueryExecutionDocument(
    val kind: QueryExecutionKindDocument,
    val budget: QueryExecutionBudgetDocument,
)

@Serializable
enum class QueryRetentionModeDocument {
    @SerialName("discard") DISCARD,
    @SerialName("retain") RETAIN,
}

/** Each action carries exactly the input needed for that transition. */
@Serializable
@JsonClassDiscriminator("action")
sealed interface QueryRunRequest : OperationRequest {
    val executionBudget: ExecutionBudgetDocument?

    /** Semantic transition compatibility is established by plan admission. */
    @Serializable(with = QueryRunActionSerializer::class)
    @KeepGeneratedSerializer
    @SerialName("run")
    data class Run(
        val from: QueryFromDocument,
        val steps: BoundedProtocolList<QueryStepDocument>,
        val output: QueryOutputDocument,
        val execution: QueryExecutionDocument,
        val retention: QueryRetentionModeDocument = QueryRetentionModeDocument.DISCARD,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("execution_budget")
        override val executionBudget: ExecutionBudgetDocument? = null,
    ) : QueryRunRequest

    @Serializable
    @SerialName("resume")
    data class Resume(
        val continuation: QueryExecutionContinuation,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("execution_budget")
        override val executionBudget: ExecutionBudgetDocument? = null,
    ) : QueryRunRequest

    @Serializable
    @SerialName("read-result")
    @ConsistentCopyVisibility
    data class ReadResult
    private constructor(
        val result: QueryResultReference,
        val cursor: QueryResultCursor = QueryResultCursor.Start,
        val output: QueryOutputDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("execution_budget")
        override val executionBudget: ExecutionBudgetDocument? = null,
    ) : QueryRunRequest {
        init {
            if (output !is QueryOutputDocument.Symbols && output != QueryOutputDocument.BindingRows) {
                throw kotlinx.serialization.SerializationException("read-result output must be symbols or binding rows")
            }
        }

        companion object {
            fun symbols(
                result: QueryResultReference,
                cursor: QueryResultCursor = QueryResultCursor.Start,
                output: QueryOutputDocument.Symbols,
                executionBudget: ExecutionBudgetDocument? = null,
            ): ReadResult = ReadResult(result, cursor, output, executionBudget)

            fun bindingRows(
                result: QueryResultReference,
                cursor: QueryResultCursor = QueryResultCursor.Start,
                executionBudget: ExecutionBudgetDocument? = null,
            ): ReadResult = ReadResult(result, cursor, QueryOutputDocument.BindingRows, executionBudget)
        }
    }
}

internal object QueryRunActionSerializer : KSerializer<QueryRunRequest.Run> {
    private val delegate = QueryRunRequest.Run.generatedSerializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryRunRequest.Run) {
        delegate.serialize(encoder, value.requireCanonicalSyntax())
    }

    override fun deserialize(decoder: Decoder): QueryRunRequest.Run =
        delegate.deserialize(decoder).requireCanonicalSyntax()
}

private fun QueryRunRequest.Run.requireCanonicalSyntax(): QueryRunRequest.Run =
    if (hasCanonicalRequestSyntax()) this
    else throw SerializationException("QueryRunRequest rejected non-canonical query syntax")

private fun QueryRunRequest.Run.hasCanonicalRequestSyntax(): Boolean {
    val sourceIsCanonical =
        when (val source = from) {
            is QueryFromDocument.Symbols -> source.discovery.isCanonical()
            is QueryFromDocument.References -> source.values.values.isNotEmpty()
            is QueryFromDocument.Result -> source.rowIds?.values?.isUnique() ?: true
        }
    if (!sourceIsCanonical) return false
    if (!steps.values.all(QueryStepDocument::hasCanonicalSyntax)) return false
    return when (val projection = output) {
        is QueryOutputDocument.Symbols -> projection.fields.values.isUnique()
        QueryOutputDocument.Occurrences -> true
        QueryOutputDocument.TraversalRecords -> true
        QueryOutputDocument.BindingRows -> true
    }
}

private fun QueryDiscoveryDocument.isCanonical(): Boolean {
    if (!scope.sourceSets.values.isUniqueNonEmpty() || !declarationKinds.values.isUniqueNonEmpty()) return false
    if (QueryDeclarationKindDocument.CONSTRUCTOR in declarationKinds.values) return false
    val name = match as? QueryMatchDocument.Name
    if (name != null && name.text.value.length > 256) return false
    val packageName = scope.packageName?.name?.value
    if (packageName != null && !QUERY_PACKAGE_NAME.matches(packageName)) return false
    val directory = scope.directory?.path?.value
    if (
        directory != null &&
            directory != "." &&
            (directory.startsWith('/') ||
                directory.any(Char::isISOControl) ||
                directory.split('/').any { it.isBlank() || it == "." || it == ".." })
    ) {
        return false
    }
    return true
}

private fun QueryStepDocument.hasCanonicalSyntax(): Boolean =
    when (this) {
        is QueryStepDocument.Related,
        is QueryStepDocument.Walk,
        is QueryStepDocument.ProjectBinding,
        QueryStepDocument.Distinct -> true
        is QueryStepDocument.Join ->
            (right.rowIds?.values?.isUnique() ?: true) &&
                ((mode as? QueryJoinModeDocument.Inner)?.let { it.leftName != it.rightName } ?: true)
        is QueryStepDocument.Concat ->
            when (val source = input) {
                is QueryFromDocument.References -> source.values.values.isNotEmpty()
                is QueryFromDocument.Result -> source.rowIds?.values?.isUnique() ?: true
            }
        is QueryStepDocument.Intersect -> right.rowIds?.values?.isUnique() ?: true
        is QueryStepDocument.Union -> right.rowIds?.values?.isUnique() ?: true
        is QueryStepDocument.Difference -> right.rowIds?.values?.isUnique() ?: true
        is QueryStepDocument.Where ->
            when (val value = predicate) {
                is QueryPredicateDocument.Visibility -> value.values.values.isUniqueNonEmpty()
                is QueryPredicateDocument.Primitive -> value.value.value.length <= MAX_QUERY_PRIMITIVE_VALUE_LENGTH
            }
    }

private fun <Value> List<Value>.isUniqueNonEmpty(): Boolean = isNotEmpty() && isUnique()

private fun <Value> List<Value>.isUnique(): Boolean = size == distinct().size

private val QUERY_PACKAGE_NAME = Regex("(?!.*\\.(?:[0-9.]|$))[A-Za-z_][A-Za-z0-9_.]*")

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
    val omissions: BoundedProtocolList<QueryRelationOmissionDocument> = QueryRelationOmissionDocument.Empty,
    val walkObservations: BoundedProtocolList<QueryWalkObservationDocument> = QueryWalkObservationDocument.Empty,
    val retention: QueryResultRetention = QueryResultRetention.NotRequested,
    val nextCursor: QueryResultCursor? = null,
    val executionBudget: ExecutionBudgetReport? = null,
    val referenceAcquisitions: ReadReferenceAcquisitions? = null,
) : OperationResult

enum class QuerySourceRejectionReason {
    UNSUPPORTED_DECLARATION_KIND
}

enum class QueryReferenceRejectionReason {
    REVALIDATION_WRONG_KIND,
    REVALIDATION_UNRETAINED,
    REVALIDATION_EXPIRED,
    REVALIDATION_CAPACITY,
    REVALIDATION_WORK_LIMIT_REACHED,
    REVALIDATION_TIME_LIMIT_REACHED,
    REVALIDATION_RETIRED,
    REVALIDATION_CAPTURE_UNAVAILABLE,
    REVALIDATION_WORKSPACE_MISMATCH,
    REVALIDATION_OWNER_MISMATCH,
    REVALIDATION_WORKSPACE_NOT_READY,
    REVALIDATION_BASIS_MOVED,
    REVALIDATION_CONTENT_CHANGED,
    REVALIDATION_CONTENT_UNCOMMITTED,
    REVALIDATION_SCOPE_REJECTED,
    REVALIDATION_DECLARATION_MISSING,
    REVALIDATION_UNSUPPORTED_DECLARATION,
    REVALIDATION_AMBIGUOUS,
    REVALIDATION_COMPILER_IDENTITY_CHANGED,
    REVALIDATION_COMPILER_UNAVAILABLE,
    WRONG_KIND,
    MALFORMED,
    INCOMPATIBLE_WORKSPACE,
    STALE_GENERATION,
    STALE_AUTHORITY,
    INCOMPATIBLE_AUTHORITY,
    INCOMPATIBLE_REFERENCE_VERSION,
}

sealed interface QueryRunRejection : QueryRunFailure {
    data object WorkspaceNotReady : QueryRunRejection

    data class ReferenceRejected(
        val position: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryRunRejection

    data class StepReferenceRejected(
        val stepPosition: ProtocolOffset,
        val referencePosition: ProtocolOffset,
        val reason: QueryReferenceRejectionReason,
    ) : QueryRunRejection

    data class SourceRejected(
        val kind: QueryDeclarationKindDocument,
        val reason: QuerySourceRejectionReason,
    ) : QueryRunRejection

    data class ExecutionRejected(val reason: QueryExecutionRejectionDocument) : QueryRunRejection
}
