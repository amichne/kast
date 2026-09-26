@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkFailureDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class QueryWalkObservationWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    val relation: RelationKindWireDocument,
    @SerialName("maximum_depth") val maximumDepth: Int,
    @SerialName("expanded_frontier") val expandedFrontier: Int,
    val progress: TraversalProgressDocument,
    val strategy: TraversalStrategyDocument,
    @SerialName("partial_expansions") val partialExpansions: List<TraversalPartialExpansionWireDocument>,
    val coverage: QueryWalkCoverageWireDocument,
)

@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface QueryWalkCoverageWireDocument {
    @Serializable @SerialName("complete") data object Complete : QueryWalkCoverageWireDocument

    @Serializable
    @SerialName("resumable")
    data class Resumable(
        val limitations: List<TraversalLimitationWireDocument>,
        @SerialName("relation_limitations") val relationLimitations: List<RelationLimitationWireDocument>,
    ) : QueryWalkCoverageWireDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(
        val limitations: List<TraversalLimitationWireDocument>,
        @SerialName("relation_limitations") val relationLimitations: List<RelationLimitationWireDocument>,
    ) : QueryWalkCoverageWireDocument
}

@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface QueryWalkFailureWireDocument {
    @Serializable
    @SerialName("one_hop")
    data class OneHop(val reason: QueryRelationFailureWireDocument) : QueryWalkFailureWireDocument

    @Serializable
    @SerialName("required_evidence_unavailable")
    data object RequiredEvidenceUnavailable : QueryWalkFailureWireDocument

    @Serializable
    @SerialName("required_evidence_stale")
    data object RequiredEvidenceStale : QueryWalkFailureWireDocument

    @Serializable
    @SerialName("reader_contract_violation")
    data object ReaderContractViolation : QueryWalkFailureWireDocument

    @Serializable
    @SerialName("traversal_contract_violation")
    data object TraversalContractViolation : QueryWalkFailureWireDocument
}

internal fun QueryWalkFailureDocument.toWireDocument(): QueryWalkFailureWireDocument =
    when (this) {
        is QueryWalkFailureDocument.OneHop ->
            QueryWalkFailureWireDocument.OneHop(QueryRelationFailureWireDocument.valueOf(reason.name))
        QueryWalkFailureDocument.RequiredEvidenceUnavailable -> QueryWalkFailureWireDocument.RequiredEvidenceUnavailable
        QueryWalkFailureDocument.RequiredEvidenceStale -> QueryWalkFailureWireDocument.RequiredEvidenceStale
        QueryWalkFailureDocument.ReaderContractViolation -> QueryWalkFailureWireDocument.ReaderContractViolation
        QueryWalkFailureDocument.TraversalContractViolation -> QueryWalkFailureWireDocument.TraversalContractViolation
    }

internal fun QueryWalkFailureWireDocument.toContract(): QueryWalkFailureDocument =
    when (this) {
        is QueryWalkFailureWireDocument.OneHop ->
            QueryWalkFailureDocument.OneHop(
                io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument.valueOf(reason.name)
            )
        QueryWalkFailureWireDocument.RequiredEvidenceUnavailable -> QueryWalkFailureDocument.RequiredEvidenceUnavailable
        QueryWalkFailureWireDocument.RequiredEvidenceStale -> QueryWalkFailureDocument.RequiredEvidenceStale
        QueryWalkFailureWireDocument.ReaderContractViolation -> QueryWalkFailureDocument.ReaderContractViolation
        QueryWalkFailureWireDocument.TraversalContractViolation -> QueryWalkFailureDocument.TraversalContractViolation
    }

internal fun QueryWalkObservationDocument.toWireDocument() =
    QueryWalkObservationWireDocument(
        subject = QueryReferenceWireDocument.ExactSymbol(subject.token.value),
        relation = relation.toWireDocument(),
        maximumDepth = maximumDepth.value,
        expandedFrontier = expandedFrontier.value,
        progress = progress,
        strategy = strategy,
        partialExpansions = partialExpansions.values.map(TraversalPartialExpansionDocument::toWireDocument),
        coverage = coverage.toWireDocument(),
    )

private fun QueryWalkCoverageDocument.toWireDocument(): QueryWalkCoverageWireDocument =
    when (this) {
        QueryWalkCoverageDocument.Complete -> QueryWalkCoverageWireDocument.Complete
        is QueryWalkCoverageDocument.Resumable ->
            QueryWalkCoverageWireDocument.Resumable(
                limitations.map { TraversalLimitationWireDocument.valueOf(it.name) },
                relationLimitations.map { RelationLimitationWireDocument.valueOf(it.name) },
            )
        is QueryWalkCoverageDocument.TerminalIncomplete ->
            QueryWalkCoverageWireDocument.TerminalIncomplete(
                limitations.map { TraversalLimitationWireDocument.valueOf(it.name) },
                relationLimitations.map { RelationLimitationWireDocument.valueOf(it.name) },
            )
    }

internal fun QueryWalkObservationWireDocument.toContract(): WireDocumentConversion<QueryWalkObservationDocument> =
    ProtocolText.parse(subject.token).toWireDocumentConversion().flatMapConverted { token ->
        ProtocolCount.parse(maximumDepth).toWireDocumentConversion().flatMapConverted { depth ->
            QueryExpandedFrontierDocument.parse(expandedFrontier).toWireDocumentConversion().flatMapConverted { frontier
                ->
                partialExpansions.convertEach(TraversalPartialExpansionWireDocument::toContract).flatMapConverted {
                    partials ->
                    BoundedProtocolList.create(partials).toWireDocumentConversion().flatMapConverted { bounded ->
                        coverage.toContract().mapConverted { admittedCoverage ->
                            QueryWalkObservationDocument(
                                subject = QueryReferenceDocument.ExactSymbol(token),
                                relation = relation.toContract(),
                                maximumDepth = depth,
                                expandedFrontier = frontier,
                                progress = progress,
                                strategy = strategy,
                                partialExpansions = bounded,
                                coverage = admittedCoverage,
                            )
                        }
                    }
                }
            }
        }
    }

private fun QueryWalkCoverageWireDocument.toContract(): WireDocumentConversion<QueryWalkCoverageDocument> =
    when (this) {
        QueryWalkCoverageWireDocument.Complete -> WireDocumentConversion.Converted(QueryWalkCoverageDocument.Complete)
        is QueryWalkCoverageWireDocument.Resumable ->
            QueryWalkCoverageDocument.resumable(
                    limitations.map { TraversalLimitationDocument.valueOf(it.name) },
                    relationLimitations.map { it.toContract() },
                )
                .toWireDocumentConversion()
        is QueryWalkCoverageWireDocument.TerminalIncomplete ->
            QueryWalkCoverageDocument.terminalIncomplete(
                    limitations.map { TraversalLimitationDocument.valueOf(it.name) },
                    relationLimitations.map { it.toContract() },
                )
                .toWireDocumentConversion()
    }
