package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path

internal class QueryImpactPeerTestFixture(
    kind: BoundaryKind = BoundaryKind.SERIALIZATION,
    root: String = "/peer",
    generation: Long = 19,
) {
    val server = QueryImpactLedgerFixture()
    val peerLease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root)).peerValue(),
            EvidenceGeneration.parse(generation).peerValue(),
        )
    private val location =
        SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.SYMBOL,
                "owner",
                peerLease,
                Path.of("$root/File.kt"),
                "file://$root/File.kt",
                0,
            )
            .peerValue()
            .location as SymbolDiscoveryCandidateLocation.Declaration
    private val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                location.file,
                0,
                200,
                "owner",
                "fixture.owner",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("fixture.owner", null, emptyList(), emptyList(), 0).peerValue(),
            )
            .peerValue()
    val owner = RelationEndpoint.resolve(peerLease, server.scope, evidence).peerValue()
    val site =
        ValueSite.fromCompiler(
                owner,
                ExactDeclarationTextRange.parse(50, 51).peerValue(),
                ValueRole.PropertyAssignment,
            )
            .peerValue()
    val request =
        ValueSiteRevalidationRequest.create(
                SymbolSelector.issue(peerLease, server.scope, evidence),
                site.range,
                ValueSiteRoleClaim.PropertyAssignment,
                server.domain.budget,
            )
            .peerValue()
    val selection =
        QueryImpactRequestedSite.admit(
                request,
                RevalidatedValueSite.fromCompiler(request, site).peerValue(),
                RelationWorkCount.parse(1).peerValue(),
            )
            .peerValue()
    val receipt =
        QueryImpactPeerAcquisitionReceipt.fromCompletedRead(
                peerLease,
                server.domain.budget,
                RelationWorkCount.parse(3).peerValue(),
                QueryImpactPeerElapsedNanos.parse(10).peerValue(),
            )
            .peerValue()
    val admission = QueryImpactPeerSiteAdmission.admit(selection, receipt).peerValue()
    private val version = ModelVersion.parse(1).peerValue()
    private val contract = BoundaryContractIdentity(id("wire"), version)
    val sourcePosition = BoundaryPosition.at(server.producer, kind, contract, id("payload"))
    val targetPosition = BoundaryPosition.at(site, kind, contract, id("payload"))
    private val reference =
        ModelRuleReference(ContractModelIdentity(id("wire-model"), version, id("review:914")), id("peer"))
    val model =
        BoundaryModel.Continuation.admit(
                reference,
                sourcePosition.reference,
                targetPosition.reference,
                sourcePosition,
                targetPosition,
                emptySet(),
            )
            .peerValue()
    private val boundaryAdmission = QueryImpactPeerBoundary.admit(server.lease, model, admission)
    val boundary: QueryImpactPeerBoundary
        get() = boundaryAdmission.peerValue()

    fun source(peers: List<QueryImpactPeerBoundary>) =
        QueryImpactSource.admit(
            listOf(server.producerWitness),
            emptyList(),
            listOf(model),
            server.domain.boundary,
            peerBoundaries = peers,
        )

    private fun id(value: String) = ModelIdentifier.parse(value).peerValue()
}

internal fun <V> Refinement<V, *>.peerValue(): V = (this as Refinement.Refined).value
