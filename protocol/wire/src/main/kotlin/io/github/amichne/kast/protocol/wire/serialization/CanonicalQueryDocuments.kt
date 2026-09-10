@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface QueryReferenceWireDocument {
    val token: String

    @Serializable @SerialName("declaration-candidate")
    data class DeclarationCandidate(override val token: String) : QueryReferenceWireDocument

    @Serializable @SerialName("exact-symbol")
    data class ExactSymbol(override val token: String) : QueryReferenceWireDocument
}

@Serializable internal enum class QueryDeclarationKindWireDocument {
    @SerialName("class") CLASS,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}
@Serializable
internal data class QueryRunResultWireDocument(
    val items: List<QueryResultItemWireDocument>,
    val failures: List<QueryItemFailureWireDocument>,
)

@Serializable
internal sealed interface QueryResultItemWireDocument {
    @Serializable @SerialName("candidate")
    data class Candidate(
        val ref: QueryReferenceWireDocument.DeclarationCandidate,
        val kind: SymbolCategoryWireDocument,
        val name: String?,
        val location: QueryCandidateLocationWireDocument?,
    ) : QueryResultItemWireDocument

    @Serializable @SerialName("exact-symbol")
    data class ExactSymbol(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val kind: SymbolKindWireDocument,
        val name: String?,
        val location: QueryExactLocationWireDocument?,
        val signature: CompilerSignatureWireDocument?,
        val connections: List<RelationFactWireDocument>,
    ) : QueryResultItemWireDocument
}

@Serializable
internal data class QueryCandidateLocationWireDocument(val file: String, val offset: Int)

@Serializable
internal data class QueryExactLocationWireDocument(val file: String, val range: SourceRangeWireDocument)

@Serializable
internal sealed interface QueryItemFailureWireDocument {
    @Serializable @SerialName("refinement")
    data class Refinement(
        val ref: QueryReferenceWireDocument.DeclarationCandidate,
        val reason: QueryExactFailureWireDocument,
    ) : QueryItemFailureWireDocument

    @Serializable @SerialName("exact-reference")
    data class ExactReference(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val reason: QueryExactFailureWireDocument,
    ) : QueryItemFailureWireDocument

    @Serializable @SerialName("predicate")
    data class Predicate(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val reason: QueryPredicateFailureWireDocument,
    ) : QueryItemFailureWireDocument

    @Serializable @SerialName("relation")
    data class Relation(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val relation: RelationKindWireDocument,
        val reason: QueryRelationFailureWireDocument,
    ) : QueryItemFailureWireDocument
}

@Serializable internal enum class QueryExactFailureWireDocument {
    WORKSPACE_NOT_READY, WORKSPACE_ROOT_MISMATCH, STALE_GENERATION, SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE, STALE_LOCATION, OUTSIDE_SCOPE, AMBIGUOUS_DECLARATION,
    UNSUPPORTED_DECLARATION, COMPILER_IDENTITY_UNAVAILABLE, DECLARATION_MOVED_OR_CHANGED,
    COMPILER_CONTRACT_VIOLATION,
}
@Serializable internal enum class QueryPredicateFailureWireDocument {
    PREDICATE_UNPROVEN, WORKSPACE_NOT_READY, WORKSPACE_ROOT_MISMATCH, STALE_GENERATION,
    SOURCE_STATE_MISMATCH, CANDIDATE_STALE, SOURCE_SELECTOR_STALE, SOURCE_SNAPSHOT_MISMATCH,
    SOURCE_UNAVAILABLE, DOCUMENT_DIRTY, PSI_DOCUMENT_UNCOMMITTED, OUTSIDE_SOURCE_SCOPE,
    ANCHOR_NOT_FOUND, AMBIGUOUS_ANCHOR, REGION_NOT_APPLICABLE, REGION_ABSENT,
    COMPILER_ANALYSIS_UNAVAILABLE, CONTRACT_VIOLATION,
}
@Serializable internal enum class QueryRelationFailureWireDocument {
    WORKSPACE_NOT_READY, WORKSPACE_ROOT_MISMATCH, STALE_GENERATION, SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE, STALE_SELECTOR, OUTSIDE_SCOPE, AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT, COMPILER_IDENTITY_UNAVAILABLE, CONTINUATION_CURSOR_MOVED,
    COMPILER_CONTRACT_VIOLATION,
}

@Serializable
internal data class QueryRunQualificationWireDocument(
    val knownMinimum: Int,
    val limitations: List<QueryLimitationWireDocument>,
)

@Serializable internal enum class QueryLimitationWireDocument {
    @SerialName("result-limit-reached") RESULT_LIMIT_REACHED,
    @SerialName("byte-limit-reached") BYTE_LIMIT_REACHED,
    @SerialName("work-limit-reached") WORK_LIMIT_REACHED,
    @SerialName("time-limit-reached") TIME_LIMIT_REACHED,
    @SerialName("discovery-incomplete") DISCOVERY_INCOMPLETE,
    @SerialName("refinement-incomplete") REFINEMENT_INCOMPLETE,
    @SerialName("visibility-incomplete") VISIBILITY_INCOMPLETE,
    @SerialName("relation-incomplete") RELATION_INCOMPLETE,
}

@Serializable
internal sealed interface QueryRunRejectionWireDocument {
    @Serializable @SerialName("workspace-not-ready") data object WorkspaceNotReady : QueryRunRejectionWireDocument

    @Serializable @SerialName("plan-rejected")
    data class PlanRejected(
        val position: Int,
        val required: QueryElementTypeWireDocument,
        val actual: QueryElementTypeWireDocument,
        val correction: QueryAdmissionCorrectionWireDocument,
    ) : QueryRunRejectionWireDocument

    @Serializable @SerialName("reference-rejected")
    data class ReferenceRejected(
        val position: Int,
        val reason: QueryReferenceRejectionReasonWireDocument,
    ) : QueryRunRejectionWireDocument

    @Serializable @SerialName("source-rejected")
    data class SourceRejected(
        val kind: QueryDeclarationKindWireDocument,
        val reason: QuerySourceRejectionReasonWireDocument,
    ) : QueryRunRejectionWireDocument

    @Serializable @SerialName("execution-rejected")
    data class ExecutionRejected(val reason: QueryExecutionRejectionWireDocument) : QueryRunRejectionWireDocument
}
@Serializable internal enum class QueryExecutionRejectionWireDocument {
    @SerialName("request-rejected") REQUEST_REJECTED,
    @SerialName("discovery-rejected") DISCOVERY_REJECTED,
    @SerialName("reference-stale") REFERENCE_STALE,
    @SerialName("budget-rejected") BUDGET_REJECTED,
    @SerialName("internal-contract-violation") INTERNAL_CONTRACT_VIOLATION,
}

@Serializable internal enum class QueryElementTypeWireDocument {
    @SerialName("declaration-candidate") DECLARATION_CANDIDATE,
    @SerialName("exact-symbol") EXACT_SYMBOL,
}
@Serializable internal enum class QueryAdmissionCorrectionWireDocument {
    @SerialName("insert-inspect") INSERT_INSPECT,
    @SerialName("remove-inspect") REMOVE_INSPECT,
    @SerialName("select-symbol-output") SELECT_SYMBOL_OUTPUT,
}
@Serializable internal enum class QuerySourceRejectionReasonWireDocument {
    @SerialName("unsupported-declaration-kind") UNSUPPORTED_DECLARATION_KIND,
}
@Serializable internal enum class QueryReferenceRejectionReasonWireDocument {
    @SerialName("wrong-kind") WRONG_KIND,
    @SerialName("malformed") MALFORMED,
    @SerialName("incompatible-workspace") INCOMPATIBLE_WORKSPACE,
    @SerialName("stale-generation") STALE_GENERATION,
    @SerialName("stale-authority") STALE_AUTHORITY,
    @SerialName("incompatible-authority") INCOMPATIBLE_AUTHORITY,
    @SerialName("incompatible-reference-version") INCOMPATIBLE_REFERENCE_VERSION,
}

internal object CanonicalQuerySerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)
    val request = factory.create(QueryRunRequest.serializer())
    val result = factory.create(
        QueryRunResultWireDocument.serializer(),
        QueryRunResult::toQueryWireDocument,
        QueryRunResultWireDocument::toContract,
    )
    val qualification = factory.create(
        QueryRunQualificationWireDocument.serializer(),
        QueryRunQualification::toQueryWireDocument,
        QueryRunQualificationWireDocument::toContract,
    )
    val rejection = factory.create(
        QueryRunRejectionWireDocument.serializer(),
        QueryRunRejection::toQueryWireDocument,
        QueryRunRejectionWireDocument::toContract,
    )
}

private fun QueryReferenceDocument.toWire(): QueryReferenceWireDocument = when (this) {
    is QueryReferenceDocument.DeclarationCandidate -> QueryReferenceWireDocument.DeclarationCandidate(token.value)
    is QueryReferenceDocument.ExactSymbol -> QueryReferenceWireDocument.ExactSymbol(token.value)
}

private fun QueryReferenceWireDocument.toContract(): WireDocumentConversion<QueryReferenceDocument> =
    token.protocolText().mapConverted {
        when (this) {
            is QueryReferenceWireDocument.DeclarationCandidate -> QueryReferenceDocument.DeclarationCandidate(it)
            is QueryReferenceWireDocument.ExactSymbol -> QueryReferenceDocument.ExactSymbol(it)
        }
    }

private fun QueryRunResult.toQueryWireDocument() = QueryRunResultWireDocument(
    items.values.map(QueryResultItemDocument::toWire), failures.values.map(QueryItemFailureDocument::toWire),
)

private fun QueryRunResultWireDocument.toContract(): WireDocumentConversion<QueryRunResult> =
    items.convertEach(QueryResultItemWireDocument::toContract).flatMapConverted { queryItems ->
        queryItems.bounded().flatMapConverted { boundedItems ->
            failures.convertEach(QueryItemFailureWireDocument::toContract).flatMapConverted { queryFailures ->
                queryFailures.bounded().mapConverted { QueryRunResult(boundedItems, it) }
            }
        }
    }

private fun QueryResultItemDocument.toWire(): QueryResultItemWireDocument = when (this) {
    is QueryResultItemDocument.Candidate -> QueryResultItemWireDocument.Candidate(
        ref.toWire() as QueryReferenceWireDocument.DeclarationCandidate,
        kind.toWireDocument(), name?.value,
        location?.let { QueryCandidateLocationWireDocument(it.file.value, it.offset.value) },
    )
    is QueryResultItemDocument.ExactSymbol -> QueryResultItemWireDocument.ExactSymbol(
        ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
        kind.toWireDocument(), name?.value,
        location?.let { QueryExactLocationWireDocument(it.file.value, it.range.toWireDocument()) },
        signature?.toWireDocument(), connections.values.map(RelationFactDocument::toWireDocument),
    )
}

private fun QueryResultItemWireDocument.toContract(): WireDocumentConversion<QueryResultItemDocument> = when (this) {
    is QueryResultItemWireDocument.Candidate -> ref.token.protocolText().flatMapConverted { token ->
        optionalText(name).flatMapConverted { projectedName ->
            location.toContract().mapConverted { projectedLocation ->
                QueryResultItemDocument.Candidate(
                    QueryReferenceDocument.DeclarationCandidate(token), kind.toDiscoveryKind(), projectedName, projectedLocation,
                )
            }
        }
    }
    is QueryResultItemWireDocument.ExactSymbol -> ref.token.protocolText().flatMapConverted { token ->
        optionalText(name).flatMapConverted { projectedName ->
            location.toContract().flatMapConverted { projectedLocation ->
                optionalSignature(signature).flatMapConverted { projectedSignature ->
                    connections.convertEach(RelationFactWireDocument::toContract).flatMapConverted { facts ->
                        facts.bounded().mapConverted {
                            QueryResultItemDocument.ExactSymbol(
                                QueryReferenceDocument.ExactSymbol(token), kind.toContract(), projectedName,
                                projectedLocation, projectedSignature, it,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun QueryCandidateLocationWireDocument?.toContract(): WireDocumentConversion<QueryCandidateLocationDocument?> =
    if (this == null) WireDocumentConversion.Converted(null) else combineConverted(
        file.protocolText(), offset.protocolOffset(), ::QueryCandidateLocationDocument,
    )

private fun QueryExactLocationWireDocument?.toContract(): WireDocumentConversion<QueryExactLocationDocument?> =
    if (this == null) WireDocumentConversion.Converted(null) else combineConverted(
        file.protocolText(), range.toContract(), ::QueryExactLocationDocument,
    )

private fun optionalText(value: String?): WireDocumentConversion<ProtocolText?> =
    value?.protocolText()?.mapConverted { it } ?: WireDocumentConversion.Converted(null)

private fun optionalSignature(value: CompilerSignatureWireDocument?): WireDocumentConversion<CompilerSignatureDocument?> =
    value?.toContract()?.mapConverted { it } ?: WireDocumentConversion.Converted(null)

private fun QueryItemFailureDocument.toWire(): QueryItemFailureWireDocument = when (this) {
    is QueryItemFailureDocument.Refinement -> QueryItemFailureWireDocument.Refinement(
        ref.toWire() as QueryReferenceWireDocument.DeclarationCandidate, QueryExactFailureWireDocument.valueOf(reason.name),
    )
    is QueryItemFailureDocument.ExactReference -> QueryItemFailureWireDocument.ExactReference(
        ref.toWire() as QueryReferenceWireDocument.ExactSymbol, QueryExactFailureWireDocument.valueOf(reason.name),
    )
    is QueryItemFailureDocument.Predicate -> QueryItemFailureWireDocument.Predicate(
        ref.toWire() as QueryReferenceWireDocument.ExactSymbol, QueryPredicateFailureWireDocument.valueOf(reason.name),
    )
    is QueryItemFailureDocument.Relation -> QueryItemFailureWireDocument.Relation(
        ref.toWire() as QueryReferenceWireDocument.ExactSymbol, relation.toWireDocument(), QueryRelationFailureWireDocument.valueOf(reason.name),
    )
}

private fun QueryItemFailureWireDocument.toContract(): WireDocumentConversion<QueryItemFailureDocument> = when (this) {
    is QueryItemFailureWireDocument.Refinement -> ref.token.protocolText().mapConverted { token ->
        QueryItemFailureDocument.Refinement(QueryReferenceDocument.DeclarationCandidate(token), QueryExactFailureDocument.valueOf(reason.name))
    }
    is QueryItemFailureWireDocument.ExactReference -> ref.token.protocolText().mapConverted { token ->
        QueryItemFailureDocument.ExactReference(QueryReferenceDocument.ExactSymbol(token), QueryExactFailureDocument.valueOf(reason.name))
    }
    is QueryItemFailureWireDocument.Predicate -> ref.token.protocolText().mapConverted { token ->
        QueryItemFailureDocument.Predicate(QueryReferenceDocument.ExactSymbol(token), QueryPredicateFailureDocument.valueOf(reason.name))
    }
    is QueryItemFailureWireDocument.Relation -> ref.token.protocolText().mapConverted { token ->
        QueryItemFailureDocument.Relation(QueryReferenceDocument.ExactSymbol(token), relation.toContract(), QueryRelationFailureDocument.valueOf(reason.name))
    }
}

private fun QueryRunQualification.toQueryWireDocument() = QueryRunQualificationWireDocument(
    knownMinimum.value, limitations.map(QueryLimitationDocument::toWire),
)
private fun QueryRunQualificationWireDocument.toContract(): WireDocumentConversion<QueryRunQualification> =
    QueryKnownMinimum.parse(knownMinimum).toWireDocumentConversion().flatMapConverted { minimum ->
        QueryRunQualification.create(minimum, limitations.map(QueryLimitationWireDocument::toContract))
            .toWireDocumentConversion()
    }

private fun QueryRunRejection.toQueryWireDocument(): QueryRunRejectionWireDocument = when (this) {
    QueryRunRejection.WorkspaceNotReady -> QueryRunRejectionWireDocument.WorkspaceNotReady
    is QueryRunRejection.PlanRejected -> QueryRunRejectionWireDocument.PlanRejected(
        position.value, required.toWire(), actual.toWire(), correction.toWire(),
    )
    is QueryRunRejection.ReferenceRejected -> QueryRunRejectionWireDocument.ReferenceRejected(
        position.value, reason.toWire(),
    )
    is QueryRunRejection.SourceRejected -> QueryRunRejectionWireDocument.SourceRejected(
        kind.toWire(),
        QuerySourceRejectionReasonWireDocument.valueOf(reason.name),
    )
    is QueryRunRejection.ExecutionRejected -> QueryRunRejectionWireDocument.ExecutionRejected(QueryExecutionRejectionWireDocument.valueOf(reason.name))
}
private fun QueryRunRejectionWireDocument.toContract(): WireDocumentConversion<QueryRunRejection> = when (this) {
    QueryRunRejectionWireDocument.WorkspaceNotReady -> WireDocumentConversion.Converted(QueryRunRejection.WorkspaceNotReady)
    is QueryRunRejectionWireDocument.PlanRejected -> position.protocolOffset().mapConverted {
        QueryRunRejection.PlanRejected(it, required.toContract(), actual.toContract(), correction.toContract())
    }
    is QueryRunRejectionWireDocument.ReferenceRejected -> position.protocolOffset().mapConverted {
        QueryRunRejection.ReferenceRejected(it, reason.toContract())
    }
    is QueryRunRejectionWireDocument.SourceRejected -> WireDocumentConversion.Converted(
        QueryRunRejection.SourceRejected(
            kind.toContract(),
            QuerySourceRejectionReason.valueOf(reason.name),
        ),
    )
    is QueryRunRejectionWireDocument.ExecutionRejected -> WireDocumentConversion.Converted(
        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.valueOf(reason.name)),
    )
}

private fun QueryDeclarationKindDocument.toWire() = QueryDeclarationKindWireDocument.valueOf(name)
private fun QueryDeclarationKindWireDocument.toContract() = QueryDeclarationKindDocument.valueOf(name)
private fun QueryLimitationDocument.toWire() = QueryLimitationWireDocument.valueOf(name)
private fun QueryLimitationWireDocument.toContract() = QueryLimitationDocument.valueOf(name)
private fun QueryElementTypeDocument.toWire() = QueryElementTypeWireDocument.valueOf(name)
private fun QueryElementTypeWireDocument.toContract() = QueryElementTypeDocument.valueOf(name)
private fun QueryAdmissionCorrectionDocument.toWire() = QueryAdmissionCorrectionWireDocument.valueOf(name)
private fun QueryAdmissionCorrectionWireDocument.toContract() = QueryAdmissionCorrectionDocument.valueOf(name)
private fun QueryReferenceRejectionReason.toWire() = QueryReferenceRejectionReasonWireDocument.valueOf(name)
private fun QueryReferenceRejectionReasonWireDocument.toContract() = QueryReferenceRejectionReason.valueOf(name)

private fun String.protocolText() = ProtocolText.parse(this).toWireDocumentConversion()
private fun Int.protocolOffset() = ProtocolOffset.parse(this).toWireDocumentConversion()
private fun <Value> List<Value>.bounded() = BoundedProtocolList.create(this).toWireDocumentConversion()
