@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class QueryRunRequestWireDocument(
    val from: QueryFromWireDocument,
    val steps: List<QueryStepWireDocument>,
    val output: QueryOutputWireDocument,
    val execution: QueryExecutionWireDocument,
)

@Serializable
internal sealed interface QueryFromWireDocument {
    @Serializable @SerialName("candidates")
    data class Candidates(
        val match: QueryMatchWireDocument,
        val scope: QueryScopeWireDocument,
        val declarationKinds: List<QueryDeclarationKindWireDocument>,
    ) : QueryFromWireDocument

    @Serializable @SerialName("symbols")
    data class Symbols(
        val match: QueryMatchWireDocument,
        val scope: QueryScopeWireDocument,
        val declarationKinds: List<QueryDeclarationKindWireDocument>,
    ) : QueryFromWireDocument

    @Serializable @SerialName("references")
    data class References(val values: List<QueryReferenceWireDocument>) : QueryFromWireDocument
}

@Serializable
internal data class QueryDiscoveryWireDocument(
    val match: QueryMatchWireDocument,
    val scope: QueryScopeWireDocument,
    val declarationKinds: List<QueryDeclarationKindWireDocument>,
)

@Serializable
internal sealed interface QueryMatchWireDocument {
    @Serializable @SerialName("all") data object All : QueryMatchWireDocument

    @Serializable @SerialName("name")
    data class Name(
        val text: String,
        val matching: SymbolDiscoveryMatchWireDocument,
    ) : QueryMatchWireDocument
}

@Serializable
internal data class QueryScopeWireDocument(
    val sourceSets: List<QuerySourceSetWireDocument>,
    val directory: QueryDirectoryScopeWireDocument?,
    val packageName: QueryPackageScopeWireDocument?,
)

@Serializable
internal data class QueryDirectoryScopeWireDocument(
    val path: String,
    val containment: QueryContainmentWireDocument,
)

@Serializable
internal data class QueryPackageScopeWireDocument(
    val name: String,
    val containment: QueryContainmentWireDocument,
)

@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface QueryReferenceWireDocument {
    val token: String

    @Serializable @SerialName("declaration-candidate")
    data class DeclarationCandidate(override val token: String) : QueryReferenceWireDocument

    @Serializable @SerialName("exact-symbol")
    data class ExactSymbol(override val token: String) : QueryReferenceWireDocument
}

@Serializable
internal sealed interface QueryStepWireDocument {
    @Serializable @SerialName("inspect") data object Inspect : QueryStepWireDocument

    @Serializable @SerialName("where")
    data class Where(val predicate: QueryPredicateWireDocument) : QueryStepWireDocument

    @Serializable @SerialName("related")
    data class Related(val relation: RelationKindWireDocument) : QueryStepWireDocument

    @Serializable @SerialName("distinct") data object Distinct : QueryStepWireDocument
}

@Serializable
internal sealed interface QueryPredicateWireDocument {
    @Serializable @SerialName("visibility")
    data class Visibility(val values: List<QueryVisibilityWireDocument>) : QueryPredicateWireDocument
}

@Serializable
internal sealed interface QueryOutputWireDocument {
    @Serializable @SerialName("candidates")
    data class Candidates(val fields: List<QueryCandidateFieldWireDocument>) : QueryOutputWireDocument

    @Serializable @SerialName("symbols")
    data class Symbols(val fields: List<QuerySymbolFieldWireDocument>) : QueryOutputWireDocument
}

@Serializable internal enum class QueryDeclarationKindWireDocument {
    @SerialName("class") CLASS,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}
@Serializable internal enum class QuerySourceSetWireDocument {
    @SerialName("main") MAIN, @SerialName("test") TEST,
}
@Serializable internal enum class QueryContainmentWireDocument {
    @SerialName("direct") DIRECT, @SerialName("descendants") DESCENDANTS,
}
@Serializable internal enum class QueryVisibilityWireDocument {
    @SerialName("public") PUBLIC,
    @SerialName("protected") PROTECTED,
    @SerialName("internal") INTERNAL,
    @SerialName("private") PRIVATE,
    @SerialName("local") LOCAL,
}
@Serializable internal enum class QueryCandidateFieldWireDocument {
    @SerialName("name") NAME, @SerialName("location") LOCATION,
}
@Serializable internal enum class QuerySymbolFieldWireDocument {
    @SerialName("name") NAME,
    @SerialName("location") LOCATION,
    @SerialName("signature") SIGNATURE,
}
@Serializable internal data class QueryExecutionWireDocument(
    val kind: QueryExecutionKindWireDocument,
    val budget: QueryExecutionBudgetWireDocument,
)
@Serializable internal enum class QueryExecutionKindWireDocument {
    @SerialName("exhaustive") EXHAUSTIVE,
}
@Serializable internal enum class QueryExecutionBudgetWireDocument {
    @SerialName("interactive") INTERACTIVE,
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
}

internal object CanonicalQuerySerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)
    val request = factory.create(
        QueryRunRequestWireDocument.serializer(),
        QueryRunRequest::toQueryWireDocument,
        QueryRunRequestWireDocument::toContract,
    )
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

sealed interface QueryRequestFragmentAdmission {
    data class Admitted(val request: QueryRunRequest) : QueryRequestFragmentAdmission
    data object Rejected : QueryRequestFragmentAdmission
}

/** Strictly decodes the four object-valued CLI fragments used by the hosted query transport. */
object CanonicalQueryRequestFragments {
    fun admit(
        from: String,
        steps: String,
        output: String,
        execution: String,
    ): QueryRequestFragmentAdmission {
        val document = try {
            QueryRunRequestWireDocument(
                from = wireJson.decodeFromJsonElement(
                    QueryFromWireDocument.serializer(),
                    wireJson.parseToJsonElement(from),
                ),
                steps = wireJson.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(QueryStepWireDocument.serializer()),
                    wireJson.parseToJsonElement(steps),
                ),
                output = wireJson.decodeFromJsonElement(
                    QueryOutputWireDocument.serializer(),
                    wireJson.parseToJsonElement(output),
                ),
                execution = wireJson.decodeFromJsonElement(
                    QueryExecutionWireDocument.serializer(),
                    wireJson.parseToJsonElement(execution),
                ),
            )
        } catch (_: SerializationException) {
            return QueryRequestFragmentAdmission.Rejected
        } catch (_: IllegalArgumentException) {
            return QueryRequestFragmentAdmission.Rejected
        }
        return when (val admitted = document.toContract()) {
            is WireDocumentConversion.Converted -> QueryRequestFragmentAdmission.Admitted(admitted.value)
            WireDocumentConversion.Rejected -> QueryRequestFragmentAdmission.Rejected
        }
    }
}

private fun QueryRunRequest.toQueryWireDocument() = QueryRunRequestWireDocument(
    from.toWire(), steps.values.map(QueryStepDocument::toWire), output.toWire(), execution.toWire(),
)

private fun QueryRunRequestWireDocument.toContract(): WireDocumentConversion<QueryRunRequest> =
    from.toContract().flatMapConverted { source ->
        steps.convertEach(QueryStepWireDocument::toContract).flatMapConverted { querySteps ->
            querySteps.bounded().flatMapConverted { boundedSteps ->
                output.toContract().mapConverted { queryOutput ->
                    QueryRunRequest(source, boundedSteps, queryOutput, execution.toContract())
                }
            }
        }
    }

private fun QueryFromDocument.toWire(): QueryFromWireDocument = when (this) {
    is QueryFromDocument.Candidates -> discovery.toWire().let {
        QueryFromWireDocument.Candidates(it.match, it.scope, it.declarationKinds)
    }
    is QueryFromDocument.Symbols -> discovery.toWire().let {
        QueryFromWireDocument.Symbols(it.match, it.scope, it.declarationKinds)
    }
    is QueryFromDocument.References -> QueryFromWireDocument.References(values.values.map { it.toWire() })
}

private fun QueryFromWireDocument.toContract(): WireDocumentConversion<QueryFromDocument> = when (this) {
    is QueryFromWireDocument.Candidates -> QueryDiscoveryWireDocument(match, scope, declarationKinds)
        .toContract().mapConverted(QueryFromDocument::Candidates)
    is QueryFromWireDocument.Symbols -> QueryDiscoveryWireDocument(match, scope, declarationKinds)
        .toContract().mapConverted(QueryFromDocument::Symbols)
    is QueryFromWireDocument.References -> values.convertEach(QueryReferenceWireDocument::toContract)
        .flatMapConverted { it.bounded() }
        .mapConverted(QueryFromDocument::References)
}

private fun QueryDiscoveryDocument.toWire() = QueryDiscoveryWireDocument(
    match.toWire(), scope.toWire(), declarationKinds.values.map(QueryDeclarationKindDocument::toWire),
)

private fun QueryDiscoveryWireDocument.toContract(): WireDocumentConversion<QueryDiscoveryDocument> =
    match.toContract().flatMapConverted { queryMatch ->
        scope.toContract().flatMapConverted { queryScope ->
            declarationKinds.map(QueryDeclarationKindWireDocument::toContract).bounded()
                .mapConverted { QueryDiscoveryDocument(queryMatch, queryScope, it) }
        }
    }

private fun QueryMatchDocument.toWire(): QueryMatchWireDocument = when (this) {
    QueryMatchDocument.All -> QueryMatchWireDocument.All
    is QueryMatchDocument.Name -> QueryMatchWireDocument.Name(text.value, matching.toWireDocument())
}

private fun QueryMatchWireDocument.toContract(): WireDocumentConversion<QueryMatchDocument> = when (this) {
    QueryMatchWireDocument.All -> WireDocumentConversion.Converted(QueryMatchDocument.All)
    is QueryMatchWireDocument.Name -> text.protocolText().mapConverted {
        QueryMatchDocument.Name(it, matching.toContract())
    }
}

private fun QueryScopeDocument.toWire() = QueryScopeWireDocument(
    sourceSets.values.map(QuerySourceSetDocument::toWire),
    directory?.let { QueryDirectoryScopeWireDocument(it.path.value, it.containment.toWire()) },
    packageName?.let { QueryPackageScopeWireDocument(it.name.value, it.containment.toWire()) },
)

private fun QueryScopeWireDocument.toContract(): WireDocumentConversion<QueryScopeDocument> =
    sourceSets.map(QuerySourceSetWireDocument::toContract).bounded().flatMapConverted { sets ->
        directory.toContract().flatMapConverted { directoryScope ->
            packageName.toContract().mapConverted { packageScope ->
                QueryScopeDocument(sets, directoryScope, packageScope)
            }
        }
    }

private fun QueryDirectoryScopeWireDocument?.toContract(): WireDocumentConversion<QueryDirectoryScopeDocument?> =
    if (this == null) WireDocumentConversion.Converted(null) else path.protocolText().mapConverted {
        QueryDirectoryScopeDocument(it, containment.toContract())
    }

private fun QueryPackageScopeWireDocument?.toContract(): WireDocumentConversion<QueryPackageScopeDocument?> =
    if (this == null) WireDocumentConversion.Converted(null) else name.protocolText().mapConverted {
        QueryPackageScopeDocument(it, containment.toContract())
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

private fun QueryStepDocument.toWire(): QueryStepWireDocument = when (this) {
    QueryStepDocument.Inspect -> QueryStepWireDocument.Inspect
    is QueryStepDocument.Where -> QueryStepWireDocument.Where(predicate.toWire())
    is QueryStepDocument.Related -> QueryStepWireDocument.Related(relation.toWireDocument())
    QueryStepDocument.Distinct -> QueryStepWireDocument.Distinct
}

private fun QueryStepWireDocument.toContract(): WireDocumentConversion<QueryStepDocument> = when (this) {
    QueryStepWireDocument.Inspect -> WireDocumentConversion.Converted(QueryStepDocument.Inspect)
    is QueryStepWireDocument.Where -> predicate.toContract().mapConverted(QueryStepDocument::Where)
    is QueryStepWireDocument.Related -> WireDocumentConversion.Converted(QueryStepDocument.Related(relation.toContract()))
    QueryStepWireDocument.Distinct -> WireDocumentConversion.Converted(QueryStepDocument.Distinct)
}

private fun QueryPredicateDocument.toWire(): QueryPredicateWireDocument = when (this) {
    is QueryPredicateDocument.Visibility -> QueryPredicateWireDocument.Visibility(values.values.map(QueryVisibilityDocument::toWire))
}

private fun QueryPredicateWireDocument.toContract(): WireDocumentConversion<QueryPredicateDocument> = when (this) {
    is QueryPredicateWireDocument.Visibility -> values.map(QueryVisibilityWireDocument::toContract).bounded()
        .mapConverted(QueryPredicateDocument::Visibility)
}

private fun QueryOutputDocument.toWire(): QueryOutputWireDocument = when (this) {
    is QueryOutputDocument.Candidates -> QueryOutputWireDocument.Candidates(fields.values.map(QueryCandidateFieldDocument::toWire))
    is QueryOutputDocument.Symbols -> QueryOutputWireDocument.Symbols(fields.values.map(QuerySymbolFieldDocument::toWire))
}

private fun QueryOutputWireDocument.toContract(): WireDocumentConversion<QueryOutputDocument> = when (this) {
    is QueryOutputWireDocument.Candidates -> fields.map(QueryCandidateFieldWireDocument::toContract).bounded()
        .mapConverted(QueryOutputDocument::Candidates)
    is QueryOutputWireDocument.Symbols -> fields.map(QuerySymbolFieldWireDocument::toContract).bounded()
        .mapConverted(QueryOutputDocument::Symbols)
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
private fun QuerySourceSetDocument.toWire() = QuerySourceSetWireDocument.valueOf(name)
private fun QuerySourceSetWireDocument.toContract() = QuerySourceSetDocument.valueOf(name)
private fun QueryContainmentDocument.toWire() = QueryContainmentWireDocument.valueOf(name)
private fun QueryContainmentWireDocument.toContract() = QueryContainmentDocument.valueOf(name)
private fun QueryVisibilityDocument.toWire() = QueryVisibilityWireDocument.valueOf(name)
private fun QueryVisibilityWireDocument.toContract() = QueryVisibilityDocument.valueOf(name)
private fun QueryCandidateFieldDocument.toWire() = QueryCandidateFieldWireDocument.valueOf(name)
private fun QueryCandidateFieldWireDocument.toContract() = QueryCandidateFieldDocument.valueOf(name)
private fun QuerySymbolFieldDocument.toWire() = QuerySymbolFieldWireDocument.valueOf(name)
private fun QuerySymbolFieldWireDocument.toContract() = QuerySymbolFieldDocument.valueOf(name)
private fun QueryExecutionDocument.toWire() = QueryExecutionWireDocument(
    QueryExecutionKindWireDocument.valueOf(kind.name),
    QueryExecutionBudgetWireDocument.valueOf(budget.name),
)
private fun QueryExecutionWireDocument.toContract() = QueryExecutionDocument(
    QueryExecutionKindDocument.valueOf(kind.name),
    QueryExecutionBudgetDocument.valueOf(budget.name),
)
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
