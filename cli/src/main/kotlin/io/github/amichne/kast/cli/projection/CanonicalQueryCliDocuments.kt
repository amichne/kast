package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object CanonicalQueryCliDocuments {
    fun project(
        outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection>,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            completeFactory.create(
                QueryCompleteCliDocument(
                    CanonicalOperation.QUERY_RUN.id.value,
                    "complete",
                    result.items.values.map(QueryResultItemDocument::toCliDocument),
                    result.failures.values.map(QueryItemFailureDocument::toCliDocument),
                ),
            )
        },
        qualified = { result, qualification ->
            qualifiedFactory.create(
                QueryQualifiedCliDocument(
                    CanonicalOperation.QUERY_RUN.id.value,
                    "qualified",
                    result.items.values.map(QueryResultItemDocument::toCliDocument),
                    result.failures.values.map(QueryItemFailureDocument::toCliDocument),
                    QueryQualificationCliDocument(
                        qualification.knownMinimum.value,
                        qualification.limitations.map(Enum<*>::cliName),
                    ),
                ),
            )
        },
        rejected = { rejection ->
            rejectedFactory.create(
                QueryRejectedCliDocument(
                    CanonicalOperation.QUERY_RUN.id.value,
                    "rejected",
                    rejection.toCliDocument(),
                ),
            )
        },
    )
}

@Serializable
private data class QueryCompleteCliDocument(
    val operation: String,
    val status: String,
    val items: List<QueryResultItemCliDocument>,
    val failures: List<QueryItemFailureCliDocument>,
)

@Serializable
private data class QueryQualifiedCliDocument(
    val operation: String,
    val status: String,
    val items: List<QueryResultItemCliDocument>,
    val failures: List<QueryItemFailureCliDocument>,
    val qualification: QueryQualificationCliDocument,
)

@Serializable
private data class QueryRejectedCliDocument(
    val operation: String,
    val status: String,
    val rejection: QueryRejectionCliDocument,
)

@Serializable
private data class QueryQualificationCliDocument(
    val knownMinimum: Int,
    val limitations: List<String>,
)

@Serializable
private data class QueryReferenceCliDocument(val kind: String, val token: String)

@Serializable
private sealed interface QueryResultItemCliDocument {
    @Serializable @SerialName("candidate")
    data class Candidate(
        val ref: QueryReferenceCliDocument,
        val kind: String,
        val name: String?,
        val location: QueryCandidateLocationCliDocument?,
    ) : QueryResultItemCliDocument

    @Serializable @SerialName("exact-symbol")
    data class ExactSymbol(
        val ref: QueryReferenceCliDocument,
        val kind: String,
        val name: String?,
        val location: QueryExactLocationCliDocument?,
        val signature: CompilerSignatureCliDocument?,
        val connections: List<RelationFactCliDocument>,
    ) : QueryResultItemCliDocument {
        // Migration alias is derived from the retained exact reference, never an independent input.
        val symbol_ref: String = ref.token
    }
}

@Serializable
private data class QueryCandidateLocationCliDocument(val file: String, val offset: Int)

@Serializable
private data class QueryExactLocationCliDocument(val file: String, val range: SourceRangeCliDocument)

@Serializable
private sealed interface QueryItemFailureCliDocument {
    @Serializable @SerialName("refinement")
    data class Refinement(val ref: QueryReferenceCliDocument, val reason: String) : QueryItemFailureCliDocument

    @Serializable @SerialName("exact-reference")
    data class ExactReference(val ref: QueryReferenceCliDocument, val reason: String) : QueryItemFailureCliDocument

    @Serializable @SerialName("predicate")
    data class Predicate(val ref: QueryReferenceCliDocument, val reason: String) : QueryItemFailureCliDocument

    @Serializable @SerialName("relation")
    data class Relation(
        val ref: QueryReferenceCliDocument,
        val relation: String,
        val reason: String,
    ) : QueryItemFailureCliDocument
}

@Serializable
private sealed interface QueryRejectionCliDocument {
    @Serializable @SerialName("workspace-not-ready") data object WorkspaceNotReady : QueryRejectionCliDocument

    @Serializable @SerialName("plan-rejected")
    data class PlanRejected(
        val path: String,
        val required: String,
        val actual: String,
        val correction: String,
    ) : QueryRejectionCliDocument

    @Serializable @SerialName("reference-rejected")
    data class ReferenceRejected(val path: String, val reason: String) : QueryRejectionCliDocument

    @Serializable @SerialName("source-rejected")
    data class SourceRejected(val kind: String, val reason: String) : QueryRejectionCliDocument

    @Serializable @SerialName("execution-rejected")
    data class ExecutionRejected(val reason: String) : QueryRejectionCliDocument
}

private fun QueryResultItemDocument.toCliDocument(): QueryResultItemCliDocument = when (this) {
    is QueryResultItemDocument.Candidate -> QueryResultItemCliDocument.Candidate(
        ref.toCliDocument(), kind.cliName(), name?.value,
        location?.let { QueryCandidateLocationCliDocument(it.file.value, it.offset.value) },
    )
    is QueryResultItemDocument.ExactSymbol -> QueryResultItemCliDocument.ExactSymbol(
        ref.toCliDocument(), kind.cliName(), name?.value,
        location?.let {
            QueryExactLocationCliDocument(
                it.file.value,
                SourceRangeCliDocument(it.range.startInclusive.value, it.range.endExclusive.value),
            )
        },
        signature?.toCliDocument(),
        connections.values.map(RelationFactDocument::toCliDocument),
    )
}

private fun QueryItemFailureDocument.toCliDocument(): QueryItemFailureCliDocument = when (this) {
    is QueryItemFailureDocument.Refinement -> QueryItemFailureCliDocument.Refinement(ref.toCliDocument(), reason.cliName())
    is QueryItemFailureDocument.ExactReference -> QueryItemFailureCliDocument.ExactReference(ref.toCliDocument(), reason.cliName())
    is QueryItemFailureDocument.Predicate -> QueryItemFailureCliDocument.Predicate(ref.toCliDocument(), reason.cliName())
    is QueryItemFailureDocument.Relation -> QueryItemFailureCliDocument.Relation(
        ref.toCliDocument(), relation.cliName(), reason.cliName(),
    )
}

private fun QueryReferenceDocument.toCliDocument(): QueryReferenceCliDocument = when (this) {
    is QueryReferenceDocument.DeclarationCandidate -> QueryReferenceCliDocument("declaration-candidate", token.value)
    is QueryReferenceDocument.ExactSymbol -> QueryReferenceCliDocument("exact-symbol", token.value)
}

private fun QueryRunRejection.toCliDocument(): QueryRejectionCliDocument = when (this) {
    QueryRunRejection.WorkspaceNotReady -> QueryRejectionCliDocument.WorkspaceNotReady
    is QueryRunRejection.PlanRejected -> QueryRejectionCliDocument.PlanRejected(
        "steps[${position.value}]", required.cliName(), actual.cliName(), correction.cliName(),
    )
    is QueryRunRejection.ReferenceRejected -> QueryRejectionCliDocument.ReferenceRejected(
        "from.values[${position.value}]", reason.cliName(),
    )
    is QueryRunRejection.SourceRejected -> QueryRejectionCliDocument.SourceRejected(
        kind.cliName(),
        reason.cliName(),
    )
    is QueryRunRejection.ExecutionRejected -> QueryRejectionCliDocument.ExecutionRejected(reason.cliName())
}

private val completeFactory = CliJsonDocument.generated(QueryCompleteCliDocument.serializer())
private val qualifiedFactory = CliJsonDocument.generated(QueryQualifiedCliDocument.serializer())
private val rejectedFactory = CliJsonDocument.generated(QueryRejectedCliDocument.serializer())
