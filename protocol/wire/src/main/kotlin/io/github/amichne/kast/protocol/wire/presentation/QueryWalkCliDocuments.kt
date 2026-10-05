@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkFailureDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.protocol.wire.TraversalReferenceObservationWireDocument
import io.github.amichne.kast.protocol.wire.toWireDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable internal data class QueryTraversalRecordCliDocument(val depth: Int, val relation: RelationFactCliDocument)

internal fun TraversalRecordDocument.toQueryCliDocument() =
    QueryTraversalRecordCliDocument(depth.value, relation.toCliDocument())

@Serializable
internal data class QueryWalkObservationCliDocument(
    val subject: String,
    val relation: String,
    @SerialName("maximum_depth") val maximumDepth: Int,
    @SerialName("expanded_frontier") val expandedFrontier: Int,
    val progress: TraversalProgressDocument,
    val strategy: TraversalStrategyDocument,
    @SerialName("partial_expansions") val partialExpansions: List<TraversalPartialExpansionCliDocument>,
    val coverage: QueryWalkCoverageCliDocument,
    @SerialName("requested_domain")
    val requestedDomain: io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain")
    val effectiveDomain: io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument,
    @SerialName("domain_fingerprint")
    val domainFingerprint: io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint,
    @SerialName("inherited_omissions") val inheritedOmissions: List<TraversalPartialExpansionCliDocument>,
    @SerialName("reference_occurrences") val referenceOccurrences: List<TraversalReferenceObservationWireDocument>,
    @SerialName("scope_exclusions")
    val scopeExclusions: List<io.github.amichne.kast.protocol.wire.QueryWalkScopeExclusionWireDocument>,
    @SerialName("callback_observations")
    val callbackObservations: List<io.github.amichne.kast.protocol.wire.QueryWalkCallbackObservationWireDocument>,
)

@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface QueryWalkCoverageCliDocument {
    @Serializable @SerialName("complete") data object Complete : QueryWalkCoverageCliDocument

    @Serializable
    @SerialName("resumable")
    data class Resumable(
        val limitations: List<String>,
        @SerialName("relation_limitations") val relationLimitations: List<String>,
    ) : QueryWalkCoverageCliDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(
        val limitations: List<String>,
        @SerialName("relation_limitations") val relationLimitations: List<String>,
    ) : QueryWalkCoverageCliDocument
}

internal fun QueryWalkObservationDocument.toQueryCliDocument() =
    QueryWalkObservationCliDocument(
        subject.token.value,
        relation.cliName(),
        maximumDepth.value,
        expandedFrontier.value,
        progress,
        strategy,
        partialExpansions.values.map { it.toCliDocument() },
        when (val selected = coverage) {
            QueryWalkCoverageDocument.Complete -> QueryWalkCoverageCliDocument.Complete
            is QueryWalkCoverageDocument.Resumable ->
                QueryWalkCoverageCliDocument.Resumable(
                    selected.limitations.map { it.cliName() },
                    selected.relationLimitations.map { it.cliName() },
                )
            is QueryWalkCoverageDocument.TerminalIncomplete ->
                QueryWalkCoverageCliDocument.TerminalIncomplete(
                    selected.limitations.map { it.cliName() },
                    selected.relationLimitations.map { it.cliName() },
                )
        },
        requestedDomain,
        effectiveDomain,
        domainFingerprint,
        inheritedOmissions.values.map { it.toCliDocument() },
        referenceOccurrences.values.map { it.toWireDocument() },
        scopeExclusions.values.map { it.toWireDocument() },
        callbackObservations.values.map { it.toWireDocument() },
    )

@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface QueryWalkFailureCliDocument {
    @Serializable
    @SerialName("one_hop")
    data class OneHop(
        @Serializable(with = QueryRelationFailureCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
    ) : QueryWalkFailureCliDocument

    @Serializable
    @SerialName("required_evidence_unavailable")
    data object RequiredEvidenceUnavailable : QueryWalkFailureCliDocument

    @Serializable @SerialName("required_evidence_stale") data object RequiredEvidenceStale : QueryWalkFailureCliDocument

    @Serializable
    @SerialName("reader_contract_violation")
    data object ReaderContractViolation : QueryWalkFailureCliDocument

    @Serializable
    @SerialName("traversal_contract_violation")
    data object TraversalContractViolation : QueryWalkFailureCliDocument
}

internal fun QueryWalkFailureDocument.toQueryCliDocument(): QueryWalkFailureCliDocument =
    when (this) {
        is QueryWalkFailureDocument.OneHop -> QueryWalkFailureCliDocument.OneHop(reason)
        QueryWalkFailureDocument.RequiredEvidenceUnavailable -> QueryWalkFailureCliDocument.RequiredEvidenceUnavailable
        QueryWalkFailureDocument.RequiredEvidenceStale -> QueryWalkFailureCliDocument.RequiredEvidenceStale
        QueryWalkFailureDocument.ReaderContractViolation -> QueryWalkFailureCliDocument.ReaderContractViolation
        QueryWalkFailureDocument.TraversalContractViolation -> QueryWalkFailureCliDocument.TraversalContractViolation
    }
