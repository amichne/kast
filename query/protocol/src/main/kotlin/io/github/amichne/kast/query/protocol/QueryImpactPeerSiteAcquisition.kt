package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactPeerProofFailureDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryImpactPeerProofFailure
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Reuses declaration and site acquisition inside the peer's admitted read; completion remains the host's duty. */
suspend fun QueryImpactPeerSelection.acquireSite(
    current: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
    compiler: ValueProducerSeedCompilerPort,
    grant: RelationBudget,
): Refinement<QueryImpactRequestedSite, QueryRunRejection> {
    if (!expectedBasis.matchesBasis(current.identity))
        return impactFailure(QueryImpactSourceFailureCode.STALE_BOUNDARY_SITE, modelPosition.value)
    val bytes =
        when (val parsed = QueryByteLimit.parse(grant.returnedBytes.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected ->
                return impactFailure(QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED, modelPosition.value)
        }
    val references = authority.admitReadReferences(declarations.map { it.reference }, current)
    return QueryImpactSourceAcquisition(
            lease = current,
            authority = references,
            compiler = compiler,
            budget = QueryBudget(grant.resources, bytes, bytes),
            domain = RelationSearchBoundary.WORKSPACE_EXPANSION,
        )
        .acquirePeerSite(this)
}

/** Every private proof rejection keeps its exact finite cause through the public boundary. */
fun QueryImpactPeerProofFailure.peerRejection(position: ProtocolOffset): QueryRunRejection =
    QueryRunRejection.ImpactSourceRejected(
        QueryImpactSourceFailureDocument.PeerProof(
            when (this) {
                QueryImpactPeerProofFailure.WORK_RECEIPT_EXCEEDS_GRANT ->
                    ImpactPeerProofFailureDocument.WORK_RECEIPT_EXCEEDS_GRANT
                QueryImpactPeerProofFailure.TIME_RECEIPT_EXCEEDS_GRANT ->
                    ImpactPeerProofFailureDocument.TIME_RECEIPT_EXCEEDS_GRANT
                QueryImpactPeerProofFailure.SITE_WORK_EXCEEDS_ACQUISITION_WORK ->
                    ImpactPeerProofFailureDocument.SITE_WORK_EXCEEDS_ACQUISITION_WORK
                QueryImpactPeerProofFailure.SITE_GRANT_EXCEEDS_ACQUISITION_GRANT ->
                    ImpactPeerProofFailureDocument.SITE_GRANT_EXCEEDS_ACQUISITION_GRANT
                QueryImpactPeerProofFailure.TARGET_BASIS_MISMATCH ->
                    ImpactPeerProofFailureDocument.TARGET_BASIS_MISMATCH
                QueryImpactPeerProofFailure.TARGET_IDENTITY_MISMATCH ->
                    ImpactPeerProofFailureDocument.TARGET_IDENTITY_MISMATCH
                QueryImpactPeerProofFailure.SOURCE_BASIS_MISMATCH ->
                    ImpactPeerProofFailureDocument.SOURCE_BASIS_MISMATCH
                QueryImpactPeerProofFailure.SAME_SOURCE_ROOT -> ImpactPeerProofFailureDocument.SAME_SOURCE_ROOT
                QueryImpactPeerProofFailure.CONNECTION_MISMATCH -> ImpactPeerProofFailureDocument.CONNECTION_MISMATCH
            },
            position,
        )
    )
