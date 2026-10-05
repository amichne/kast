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
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalReferenceObservationDocument
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
    @SerialName("requested_domain")
    val requestedDomain: io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain")
    val effectiveDomain: io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument,
    @SerialName("domain_fingerprint")
    val domainFingerprint: io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint,
    @SerialName("inherited_omissions") val inheritedOmissions: List<TraversalPartialExpansionWireDocument>,
    @SerialName("reference_occurrences") val referenceOccurrences: List<TraversalReferenceObservationWireDocument>,
    @SerialName("scope_exclusions") val scopeExclusions: List<QueryWalkScopeExclusionWireDocument>,
    @SerialName("callback_observations") val callbackObservations: List<QueryWalkCallbackObservationWireDocument>,
)

@Serializable
internal data class TraversalReferenceObservationWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    val depth: Int,
    val reference: RelationReferenceOccurrenceWireDocument,
)

internal fun TraversalReferenceObservationDocument.toWireDocument() =
    TraversalReferenceObservationWireDocument(
        QueryReferenceWireDocument.ExactSymbol(subject.token.value),
        depth.value,
        reference.toWireDocument(),
    )

internal fun TraversalReferenceObservationWireDocument.toContract():
    WireDocumentConversion<TraversalReferenceObservationDocument> =
    combineConverted(
        ProtocolText.parse(subject.token).toWireDocumentConversion(),
        TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
        reference.toContract(),
    ) { subject, depth, reference ->
        TraversalReferenceObservationDocument(QueryReferenceDocument.ExactSymbol(subject), depth, reference)
    }

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
        requestedDomain = requestedDomain,
        effectiveDomain = effectiveDomain,
        domainFingerprint = domainFingerprint,
        inheritedOmissions = inheritedOmissions.values.map(TraversalPartialExpansionDocument::toWireDocument),
        referenceOccurrences = referenceOccurrences.values.map(TraversalReferenceObservationDocument::toWireDocument),
        scopeExclusions = scopeExclusions.values.map { it.toWireDocument() },
        callbackObservations = callbackObservations.values.map { it.toWireDocument() },
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
                partialExpansions.convertBounded(TraversalPartialExpansionWireDocument::toContract).flatMapConverted {
                    partials ->
                    retainedWalkEvidence(token, depth, frontier, partials)
                }
            }
        }
    }

private fun QueryWalkObservationWireDocument.retainedWalkEvidence(
    token: ProtocolText,
    depth: ProtocolCount,
    frontier: QueryExpandedFrontierDocument,
    partials: BoundedProtocolList<TraversalPartialExpansionDocument>,
): WireDocumentConversion<QueryWalkObservationDocument> =
    inheritedOmissions.convertBounded(TraversalPartialExpansionWireDocument::toContract).flatMapConverted { inherited ->
        referenceOccurrences.convertBounded(TraversalReferenceObservationWireDocument::toContract).flatMapConverted {
            references ->
            scopeExclusions.convertBounded(QueryWalkScopeExclusionWireDocument::toContract).flatMapConverted { exits ->
                callbackObservations
                    .convertBounded(QueryWalkCallbackObservationWireDocument::toContract)
                    .flatMapConverted { callbacks ->
                        coverage.toContract().mapConverted { admittedCoverage ->
                            QueryWalkObservationDocument(
                                subject = QueryReferenceDocument.ExactSymbol(token),
                                relation = relation.toContract(),
                                maximumDepth = depth,
                                expandedFrontier = frontier,
                                progress = progress,
                                strategy = strategy,
                                partialExpansions = partials,
                                coverage = admittedCoverage,
                                requestedDomain = requestedDomain,
                                effectiveDomain = effectiveDomain,
                                domainFingerprint = domainFingerprint,
                                inheritedOmissions = inherited,
                                referenceOccurrences = references,
                                scopeExclusions = exits,
                                callbackObservations = callbacks,
                            )
                        }
                    }
            }
        }
    }

private fun <Input, Output> List<Input>.convertBounded(
    convert: (Input) -> WireDocumentConversion<Output>
): WireDocumentConversion<BoundedProtocolList<Output>> =
    convertEach(convert).flatMapConverted { values ->
        BoundedProtocolList.create(values).toWireDocumentConversion()
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
