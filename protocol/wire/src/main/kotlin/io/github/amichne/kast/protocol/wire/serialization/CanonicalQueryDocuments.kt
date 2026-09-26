@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.reason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface QueryReferenceWireDocument {
    val token: String

    @Serializable
    @SerialName("declaration-candidate")
    data class DeclarationCandidate(override val token: String) : QueryReferenceWireDocument

    @Serializable
    @SerialName("exact-symbol")
    data class ExactSymbol(override val token: String) : QueryReferenceWireDocument
}

@Serializable
internal enum class QueryDeclarationKindWireDocument {
    @SerialName("class") CLASS,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}

@Serializable
internal sealed interface QueryResultItemWireDocument {
    @Serializable
    @SerialName("exact-symbol")
    data class ExactSymbol(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val kind: SymbolKindWireDocument,
        val name: String?,
        val location: QueryExactLocationWireDocument?,
        val signature: CompilerSignatureWireDocument?,
        val connections: List<RelationFactWireDocument>,
        val symbolId: String,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val source: QuerySourceWindowWireDocument? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        val rowId: String? = null,
    ) : QueryResultItemWireDocument

    @Serializable
    @SerialName("occurrence")
    data class Occurrence(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val relation: RelationFactWireDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        val rowId: String? = null,
    ) : QueryResultItemWireDocument
}

@Serializable
internal data class QueryRelationOmissionWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    val relation: RelationKindWireDocument,
    val evidence: RelationOmissionWireDocument,
)

@Serializable internal data class QueryExactLocationWireDocument(val file: String, val range: SourceRangeWireDocument)

@Serializable
internal enum class QueryExactFailureWireDocument {
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

@Serializable
internal enum class QueryPredicateFailureWireDocument {
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

@Serializable
internal enum class QueryRelationFailureWireDocument {
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

@Serializable
internal sealed interface QueryRunRejectionWireDocument {
    @Serializable @SerialName("workspace-not-ready") data object WorkspaceNotReady : QueryRunRejectionWireDocument

    @Serializable
    @SerialName("reference-rejected")
    data class ReferenceRejected(
        val position: Int,
        val reason: QueryReferenceRejectionReasonWireDocument,
    ) : QueryRunRejectionWireDocument

    @Serializable
    @SerialName("step-reference-rejected")
    data class StepReferenceRejected(
        val stepPosition: Int,
        val referencePosition: Int,
        val reason: QueryReferenceRejectionReasonWireDocument,
    ) : QueryRunRejectionWireDocument

    @Serializable
    @SerialName("source-rejected")
    data class SourceRejected(
        val kind: QueryDeclarationKindWireDocument,
        val reason: QuerySourceRejectionReasonWireDocument,
    ) : QueryRunRejectionWireDocument

    @Serializable
    @SerialName("execution-rejected")
    data class ExecutionRejected(val reason: QueryExecutionRejectionWireDocument) : QueryRunRejectionWireDocument
}

@Serializable
internal enum class QuerySourceRejectionReasonWireDocument {
    @SerialName("unsupported-declaration-kind") UNSUPPORTED_DECLARATION_KIND
}

internal object CanonicalQuerySerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)
    val request = factory.create(QueryRunRequest.serializer())
    val result =
        factory.create(
            QueryRunResultWireDocument.serializer(),
            QueryRunResult::toQueryWireDocument,
            QueryRunResultWireDocument::toContract,
        )
    val qualification =
        factory.create(
            QueryRunQualificationWireDocument.serializer(),
            QueryRunQualification::toQueryWireDocument,
            QueryRunQualificationWireDocument::toContract,
        )
    val rejection: WireValueCodec<QueryRunFailure> =
        factory.create(
            QueryRunRejectionWireDocument.serializer(),
            { value: QueryRunFailure -> value.reason().toQueryWireDocument() },
            QueryRunRejectionWireDocument::toContract,
        )
}

private fun QueryReferenceDocument.toWire(): QueryReferenceWireDocument =
    when (this) {
        is QueryReferenceDocument.DeclarationCandidate -> QueryReferenceWireDocument.DeclarationCandidate(token.value)
        is QueryReferenceDocument.ExactSymbol -> QueryReferenceWireDocument.ExactSymbol(token.value)
    }

private fun QueryRunResult.toQueryWireDocument() =
    QueryRunResultWireDocument(
        items = items.values.map(QueryResultItemDocument::toWire),
        failures = failures.values.map(QueryItemFailureDocument::toWire),
        omissions = omissions.values.map(QueryRelationOmissionDocument::toWire),
        retention = retention,
        nextCursor = nextCursor,
        executionBudget = executionBudget,
        referenceAcquisitions = referenceAcquisitions,
    )

private fun QueryRunResultWireDocument.toContract(): WireDocumentConversion<QueryRunResult> =
    items.convertEach(QueryResultItemWireDocument::toContract).flatMapConverted { queryItems ->
        queryItems.bounded().flatMapConverted { boundedItems ->
            failures.convertEach(QueryItemFailureWireDocument::toContract).flatMapConverted { queryFailures ->
                queryFailures.bounded().flatMapConverted { boundedFailures ->
                    omissions.convertEach(QueryRelationOmissionWireDocument::toContract).flatMapConverted { omissions ->
                        omissions.bounded().mapConverted { boundedOmissions ->
                            QueryRunResult(
                                items = boundedItems,
                                failures = boundedFailures,
                                omissions = boundedOmissions,
                                retention = retention,
                                nextCursor = nextCursor,
                                executionBudget = executionBudget,
                                referenceAcquisitions = referenceAcquisitions,
                            )
                        }
                    }
                }
            }
        }
    }

private fun QueryResultItemDocument.toWire(): QueryResultItemWireDocument =
    when (this) {
        is QueryResultItemDocument.ExactSymbol ->
            QueryResultItemWireDocument.ExactSymbol(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                kind.toWireDocument(),
                name?.value,
                location?.let { QueryExactLocationWireDocument(it.file.value, it.range.toWireDocument()) },
                signature?.toWireDocument(),
                connections.values.map(RelationFactDocument::toWireDocument),
                symbolId.value,
                source?.let {
                    QuerySourceWindowWireDocument(
                        it.text.value,
                        SourceLineRangeWireDocument(it.lines.startInclusive.value, it.lines.endInclusive.value),
                    )
                },
                rowId?.value,
            )
        is QueryResultItemDocument.Occurrence ->
            QueryResultItemWireDocument.Occurrence(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                relation.toWireDocument(),
                rowId?.value,
            )
    }

private fun QueryResultItemWireDocument.toContract(): WireDocumentConversion<QueryResultItemDocument> =
    when (this) {
        is QueryResultItemWireDocument.ExactSymbol ->
            ref.token.protocolText().flatMapConverted { token ->
                optionalText(name).flatMapConverted { projectedName ->
                    location.toContract().flatMapConverted { projectedLocation ->
                        optionalSignature(signature).flatMapConverted { projectedSignature ->
                            connections.convertEach(RelationFactWireDocument::toContract).flatMapConverted { facts ->
                                facts.bounded().flatMapConverted { boundedFacts ->
                                    source.toContract().flatMapConverted { projectedSource ->
                                        optionalRowId(rowId).flatMapConverted { projectedRowId ->
                                            io.github.amichne.kast.protocol.contract.SymbolIdDocument.parse(symbolId)
                                                .toWireDocumentConversion()
                                                .mapConverted { identity ->
                                                    QueryResultItemDocument.ExactSymbol(
                                                        QueryReferenceDocument.ExactSymbol(token),
                                                        kind.toContract(),
                                                        projectedName,
                                                        projectedLocation,
                                                        projectedSignature,
                                                        boundedFacts,
                                                        identity,
                                                        projectedSource,
                                                        projectedRowId,
                                                    )
                                                }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        is QueryResultItemWireDocument.Occurrence ->
            ref.token.protocolText().flatMapConverted { token ->
                relation.toContract().flatMapConverted { fact ->
                    optionalRowId(rowId).mapConverted { id ->
                        QueryResultItemDocument.Occurrence(QueryReferenceDocument.ExactSymbol(token), fact, id)
                    }
                }
            }
    }

private fun QueryRelationOmissionDocument.toWire(): QueryRelationOmissionWireDocument =
    QueryRelationOmissionWireDocument(
        subject = subject.toWire() as QueryReferenceWireDocument.ExactSymbol,
        relation = relation.toWireDocument(),
        evidence = evidence.toWireDocument(),
    )

private fun QueryRelationOmissionWireDocument.toContract(): WireDocumentConversion<QueryRelationOmissionDocument> =
    subject.token.protocolText().flatMapConverted { token ->
        evidence.toContract().mapConverted { omission ->
            QueryRelationOmissionDocument(QueryReferenceDocument.ExactSymbol(token), relation.toContract(), omission)
        }
    }

private fun QueryExactLocationWireDocument?.toContract(): WireDocumentConversion<QueryExactLocationDocument?> =
    if (this == null) WireDocumentConversion.Converted(null)
    else
        combineConverted(
            file.protocolText(),
            range.toContract(),
            ::QueryExactLocationDocument,
        )

private fun optionalText(value: String?): WireDocumentConversion<ProtocolText?> =
    value?.protocolText()?.mapConverted { it } ?: WireDocumentConversion.Converted(null)

private fun optionalRowId(value: String?): WireDocumentConversion<QueryResultRowReference?> =
    value?.let { QueryResultRowReference.parse(it).toWireDocumentConversion() }
        ?: WireDocumentConversion.Converted(null)

private fun optionalSignature(
    value: CompilerSignatureWireDocument?
): WireDocumentConversion<CompilerSignatureDocument?> =
    value?.toContract()?.mapConverted { it } ?: WireDocumentConversion.Converted(null)

private fun QueryItemFailureDocument.toWire(): QueryItemFailureWireDocument =
    when (this) {
        is QueryItemFailureDocument.Refinement ->
            QueryItemFailureWireDocument.Refinement(
                ref.toWire() as QueryReferenceWireDocument.DeclarationCandidate,
                QueryExactFailureWireDocument.valueOf(reason.name),
            )
        is QueryItemFailureDocument.ExactReference ->
            QueryItemFailureWireDocument.ExactReference(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                QueryExactFailureWireDocument.valueOf(reason.name),
            )
        is QueryItemFailureDocument.Predicate ->
            QueryItemFailureWireDocument.Predicate(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                QueryPredicateFailureWireDocument.valueOf(reason.name),
            )
        is QueryItemFailureDocument.Source ->
            QueryItemFailureWireDocument.Source(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                QuerySourceFailureWireDocument.valueOf(reason.name),
            )
        is QueryItemFailureDocument.Relation ->
            QueryItemFailureWireDocument.Relation(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                relation.toWireDocument(),
                QueryRelationFailureWireDocument.valueOf(reason.name),
            )
    }

private fun QueryItemFailureWireDocument.toContract(): WireDocumentConversion<QueryItemFailureDocument> =
    when (this) {
        is QueryItemFailureWireDocument.Refinement ->
            ref.token.protocolText().mapConverted { token ->
                QueryItemFailureDocument.Refinement(
                    QueryReferenceDocument.DeclarationCandidate(token),
                    QueryExactFailureDocument.valueOf(reason.name),
                )
            }
        is QueryItemFailureWireDocument.ExactReference ->
            ref.token.protocolText().mapConverted { token ->
                QueryItemFailureDocument.ExactReference(
                    QueryReferenceDocument.ExactSymbol(token),
                    QueryExactFailureDocument.valueOf(reason.name),
                )
            }
        is QueryItemFailureWireDocument.Predicate ->
            ref.token.protocolText().mapConverted { token ->
                QueryItemFailureDocument.Predicate(
                    QueryReferenceDocument.ExactSymbol(token),
                    QueryPredicateFailureDocument.valueOf(reason.name),
                )
            }
        is QueryItemFailureWireDocument.Source ->
            ref.token.protocolText().mapConverted { token ->
                QueryItemFailureDocument.Source(
                    QueryReferenceDocument.ExactSymbol(token),
                    QuerySourceFailureDocument.valueOf(reason.name),
                )
            }
        is QueryItemFailureWireDocument.Relation ->
            ref.token.protocolText().mapConverted { token ->
                QueryItemFailureDocument.Relation(
                    QueryReferenceDocument.ExactSymbol(token),
                    relation.toContract(),
                    QueryRelationFailureDocument.valueOf(reason.name),
                )
            }
    }

private fun QueryRunQualification.toQueryWireDocument() =
    QueryRunQualificationWireDocument(
        knownMinimum.value,
        limitations.map(QueryLimitationDocument::toWire),
        progress,
    )

private fun QueryRunQualificationWireDocument.toContract(): WireDocumentConversion<QueryRunQualification> =
    QueryKnownMinimum.parse(knownMinimum).toWireDocumentConversion().flatMapConverted { minimum ->
        QueryRunQualification.create(minimum, limitations.map(QueryLimitationWireDocument::toContract), progress)
            .toWireDocumentConversion()
    }

private fun QueryRunRejection.toQueryWireDocument(): QueryRunRejectionWireDocument =
    when (this) {
        QueryRunRejection.WorkspaceNotReady -> QueryRunRejectionWireDocument.WorkspaceNotReady
        is QueryRunRejection.ReferenceRejected ->
            QueryRunRejectionWireDocument.ReferenceRejected(
                position.value,
                reason.toWire(),
            )
        is QueryRunRejection.StepReferenceRejected ->
            QueryRunRejectionWireDocument.StepReferenceRejected(
                stepPosition.value,
                referencePosition.value,
                reason.toWire(),
            )
        is QueryRunRejection.SourceRejected ->
            QueryRunRejectionWireDocument.SourceRejected(
                kind.toWire(),
                QuerySourceRejectionReasonWireDocument.valueOf(reason.name),
            )
        is QueryRunRejection.ExecutionRejected ->
            QueryRunRejectionWireDocument.ExecutionRejected(QueryExecutionRejectionWireDocument.valueOf(reason.name))
    }

private fun QueryRunRejectionWireDocument.toContract(): WireDocumentConversion<QueryRunRejection> =
    when (this) {
        QueryRunRejectionWireDocument.WorkspaceNotReady ->
            WireDocumentConversion.Converted(QueryRunRejection.WorkspaceNotReady)
        is QueryRunRejectionWireDocument.ReferenceRejected ->
            position.protocolOffset().mapConverted {
                QueryRunRejection.ReferenceRejected(it, reason.toContract())
            }
        is QueryRunRejectionWireDocument.StepReferenceRejected ->
            stepPosition.protocolOffset().flatMapConverted { step ->
                referencePosition.protocolOffset().mapConverted { reference ->
                    QueryRunRejection.StepReferenceRejected(step, reference, reason.toContract())
                }
            }
        is QueryRunRejectionWireDocument.SourceRejected ->
            WireDocumentConversion.Converted(
                QueryRunRejection.SourceRejected(
                    kind.toContract(),
                    QuerySourceRejectionReason.valueOf(reason.name),
                )
            )
        is QueryRunRejectionWireDocument.ExecutionRejected ->
            WireDocumentConversion.Converted(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.valueOf(reason.name))
            )
    }

private fun QueryDeclarationKindDocument.toWire() = QueryDeclarationKindWireDocument.valueOf(name)

private fun QueryDeclarationKindWireDocument.toContract() = QueryDeclarationKindDocument.valueOf(name)

private fun QueryLimitationDocument.toWire() = QueryLimitationWireDocument.valueOf(name)

private fun QueryLimitationWireDocument.toContract() = QueryLimitationDocument.valueOf(name)

private fun QueryReferenceRejectionReason.toWire() = QueryReferenceRejectionReasonWireDocument.valueOf(name)

private fun QueryReferenceRejectionReasonWireDocument.toContract() = QueryReferenceRejectionReason.valueOf(name)

private fun String.protocolText() = ProtocolText.parse(this).toWireDocumentConversion()

private fun Int.protocolOffset() = ProtocolOffset.parse(this).toWireDocumentConversion()

private fun <Value> List<Value>.bounded() = BoundedProtocolList.create(this).toWireDocumentConversion()
