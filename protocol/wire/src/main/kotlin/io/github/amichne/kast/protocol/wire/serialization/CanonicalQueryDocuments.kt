@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryRefinementLocationDocument
import io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySourceFailureDocument
import io.github.amichne.kast.protocol.contract.QuerySourceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface QueryReferenceWireDocument {
    val token: String

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
internal data class QueryRelationOmissionWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    val relation: RelationKindWireDocument,
    val evidence: RelationOmissionWireDocument,
)

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
    @Serializable
    @SerialName("COMPLETION_UNSUPPORTED")
    data class CompletionUnsupported(val detail: QueryRunRejection.CompletionUnsupported) :
        QueryRunRejectionWireDocument

    @Serializable
    @SerialName("COMPLETION_UNPROVEN")
    data class CompletionUnproven(val detail: QueryCompletionRejectionWireDocument) : QueryRunRejectionWireDocument

    @Serializable
    @SerialName("IMPACT_EXECUTION_REJECTED")
    data class ImpactExecutionRejected(
        val cause: io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
    ) : QueryRunRejectionWireDocument

    @Serializable
    @SerialName("IMPACT_SOURCE_REJECTED")
    data class ImpactSourceRejected(
        val cause: io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
    ) : QueryRunRejectionWireDocument

    @Serializable
    @SerialName("IMPACT_PRESENTATION_REJECTED")
    data class ImpactPresentationRejected(
        val cause: io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
    ) : QueryRunRejectionWireDocument

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
            validateValue = { value ->
                when (val admitted = value.validateImpactAccounting()) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected -> Refinement.Rejected(WireFailure.InvalidImpactAccounting(admitted.failure))
                }
            },
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
        is QueryReferenceDocument.ExactSymbol -> QueryReferenceWireDocument.ExactSymbol(token.value)
    }

private fun QueryRunResult.toQueryWireDocument() =
    QueryRunResultWireDocument(
        interpretation = interpretation,
        invocation = invocation,
        evidenceWindow = evidenceWindow,
        question = question,
        impactAccounting = impactAccounting,
        items = items.values.map(QueryResultItemDocument::toWire),
        failures = failures.values.map(QueryItemFailureDocument::toWire),
        omissions = omissions.values.map(QueryRelationOmissionDocument::toWire),
        walkObservations = walkObservations.values.map(QueryWalkObservationDocument::toWireDocument),
        referenceObservations = referenceObservations.values.map { it.toWireDocument() },
        discoveryObservations = discoveryObservations.values,
        relationObservations = relationObservations.values.map { it.toWireDocument() },
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
                        omissions.bounded().flatMapConverted { boundedOmissions ->
                            walkObservations
                                .convertEach(QueryWalkObservationWireDocument::toContract)
                                .flatMapConverted { observations ->
                                    observations.bounded().flatMapConverted { boundedObservations ->
                                        referenceObservations
                                            .convertEach(RelationReferenceOccurrenceWireDocument::toContract)
                                            .flatMapConverted { references ->
                                                references.bounded().flatMapConverted { boundedReferences ->
                                                    discoveryObservations.bounded().flatMapConverted {
                                                        boundedDiscoveries ->
                                                        relationObservations.toBoundedRelations().mapConverted {
                                                            boundedRelations ->
                                                            QueryRunResult(
                                                                interpretation = interpretation,
                                                                invocation = invocation,
                                                                evidenceWindow = evidenceWindow,
                                                                question = question,
                                                                impactAccounting = impactAccounting,
                                                                items = boundedItems,
                                                                failures = boundedFailures,
                                                                omissions = boundedOmissions,
                                                                walkObservations = boundedObservations,
                                                                referenceObservations = boundedReferences,
                                                                discoveryObservations = boundedDiscoveries,
                                                                relationObservations = boundedRelations,
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
                        }
                    }
                }
            }
        }
    }

private fun List<QueryRelationObservationWireDocument>.toBoundedRelations() =
    convertEach(QueryRelationObservationWireDocument::toContract).flatMapConverted { it.bounded() }

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

private fun QueryItemFailureDocument.toWire(): QueryItemFailureWireDocument =
    when (this) {
        is QueryItemFailureDocument.Refinement ->
            QueryItemFailureWireDocument.Refinement(
                QueryRefinementLocationWireDocument(location.file.value, location.offset.value),
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
        is QueryItemFailureDocument.SourceEnumerationIncomplete ->
            QueryItemFailureWireDocument.SourceEnumerationIncomplete(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                qualification.toWireDocument(),
            )
        is QueryItemFailureDocument.Relation ->
            QueryItemFailureWireDocument.Relation(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                relation.toWireDocument(),
                QueryRelationFailureWireDocument.valueOf(reason.name),
            )
        is QueryItemFailureDocument.Walk ->
            QueryItemFailureWireDocument.Walk(
                ref.toWire() as QueryReferenceWireDocument.ExactSymbol,
                relation.toWireDocument(),
                reason.toWireDocument(),
            )
    }

private fun QueryItemFailureWireDocument.toContract(): WireDocumentConversion<QueryItemFailureDocument> =
    when (this) {
        is QueryItemFailureWireDocument.Refinement ->
            location.file.protocolText().flatMapConverted { file ->
                location.offset.protocolOffset().mapConverted { offset ->
                    QueryItemFailureDocument.Refinement(
                        QueryRefinementLocationDocument(file, offset),
                        QueryExactFailureDocument.valueOf(reason.name),
                    )
                }
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
        is QueryItemFailureWireDocument.SourceEnumerationIncomplete -> toSourceEnumerationContract()
        is QueryItemFailureWireDocument.Relation ->
            ref.token.protocolText().mapConverted { token ->
                QueryItemFailureDocument.Relation(
                    QueryReferenceDocument.ExactSymbol(token),
                    relation.toContract(),
                    QueryRelationFailureDocument.valueOf(reason.name),
                )
            }
        is QueryItemFailureWireDocument.Walk ->
            ref.token.protocolText().mapConverted { token ->
                QueryItemFailureDocument.Walk(
                    QueryReferenceDocument.ExactSymbol(token),
                    relation.toContract(),
                    reason.toContract(),
                )
            }
    }

private fun QueryItemFailureWireDocument.SourceEnumerationIncomplete.toSourceEnumerationContract():
    WireDocumentConversion<QueryItemFailureDocument.SourceEnumerationIncomplete> =
    combineConverted(ref.token.protocolText(), qualification.toContract()) { reference, progress ->
        QueryItemFailureDocument.SourceEnumerationIncomplete(QueryReferenceDocument.ExactSymbol(reference), progress)
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
        is QueryRunRejection.CompletionUnsupported -> QueryRunRejectionWireDocument.CompletionUnsupported(this)
        is QueryRunRejection.CompletionUnproven -> QueryRunRejectionWireDocument.CompletionUnproven(toCompletionWire())
        is QueryRunRejection.ImpactSourceRejected -> QueryRunRejectionWireDocument.ImpactSourceRejected(cause)
        is QueryRunRejection.ImpactExecutionRejected -> QueryRunRejectionWireDocument.ImpactExecutionRejected(cause)
        is QueryRunRejection.ImpactPresentationRejected ->
            QueryRunRejectionWireDocument.ImpactPresentationRejected(cause)
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
        is QueryRunRejectionWireDocument.CompletionUnsupported -> WireDocumentConversion.Converted(detail)
        is QueryRunRejectionWireDocument.CompletionUnproven -> detail.toContract()
        is QueryRunRejectionWireDocument.ImpactSourceRejected ->
            WireDocumentConversion.Converted(QueryRunRejection.ImpactSourceRejected(cause))
        is QueryRunRejectionWireDocument.ImpactExecutionRejected ->
            WireDocumentConversion.Converted(QueryRunRejection.ImpactExecutionRejected(cause))
        is QueryRunRejectionWireDocument.ImpactPresentationRejected ->
            WireDocumentConversion.Converted(QueryRunRejection.ImpactPresentationRejected(cause))
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
