package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPeerContinuationReasonDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.query.contract.QueryImpactPeerAcquisitionReceipt
import io.github.amichne.kast.query.contract.QueryImpactPeerBoundary
import io.github.amichne.kast.query.contract.QueryImpactPeerElapsedNanos
import io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pure admitted-domain starting facts prove projection, not installed peer compiler acquisition. */
class ImpactPeerProjectionTest {
    @Test
    fun `used peer terminal and unused model retain the same completed proof without source basis replacement`() {
        val boundary = boundary()
        val connection = BoundaryArrival.connect(boundary.model.source, boundary.model).value()
        val terminal = QueryImpactTerminal.Unresolved.PeerContinuation.admit(boundary, connection).value()
        val path = terminal.projectPeerTerminal().value() as ImpactPathTerminalDocument.UnresolvedPeerContinuation
        val compact =
            terminal.projectPeerFindingTerminal().value() as ImpactFindingTerminalDocument.UnresolvedPeerContinuation
        val model = boundary.projectPeerModelWitness().value() as ImpactWitnessDocument.PeerBoundaryModel
        assertEquals(path.admission, compact.admission)
        assertEquals(path.admission, model.admission)
        assertEquals(path.target, compact.target)
        val rule = model.rule as ImpactBoundaryRuleDocument.Continuation
        assertEquals(path.target, rule.target.site)
        assertEquals("/peer", path.admission.acquisition.completedBasis.root.value)
        assertEquals("/workspace", rule.source.site.enclosing.basis.root.value)
        assertEquals(
            9,
            (path.admission.acquisition.completedBasis as ImpactSemanticBasisDocument.Published).generation.value,
        )
        assertEquals(3, path.admission.acquisition.examinedWorkUnits.value)
        assertEquals(11, path.admission.acquisition.elapsedNanos.value)
        assertEquals(1, path.admission.selection.examinedWorkUnits.value)
        assertEquals(100_000, path.admission.acquisition.budget.maxReturnedBytes.value)
        assertEquals(ImpactPeerContinuationReasonDocument.PEER_FLOW_NOT_INVESTIGATED, path.reason)
    }

    private fun boundary(): QueryImpactPeerBoundary {
        val server = RelationPagingFixture.published()
        val peer =
            RelationPagingFixture(
                SemanticReadLease(
                    CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/peer")).value(),
                    EvidenceGeneration.parse(9).value(),
                )
            )
        val source = site(server)
        val target = site(peer)
        val admitted = admission(peer, target)
        val version = ModelVersion.parse(1).value()
        val contract = BoundaryContractIdentity(id("wire"), version)
        val sourcePosition = BoundaryPosition.at(source, BoundaryKind.SERIALIZATION, contract, id("payload"))
        val targetPosition = BoundaryPosition.at(target, BoundaryKind.SERIALIZATION, contract, id("payload"))
        val reference = ModelRuleReference(ContractModelIdentity(id("wire-model"), version, id("reviewed")), id("peer"))
        val model =
            BoundaryModel.Continuation.admit(
                    reference,
                    sourcePosition.reference,
                    targetPosition.reference,
                    sourcePosition,
                    targetPosition,
                    emptySet(),
                )
                .value()
        return QueryImpactPeerBoundary.admit(server.authority, model, admitted).value()
    }

    private fun site(fixture: RelationPagingFixture): ValueSite =
        ValueSite.fromCompiler(
                RelationRequest.start(
                        fixture.selector,
                        RelationMeaning.References,
                        fixture.budget,
                        RelationSearchBoundary.WORKSPACE_EXPANSION,
                    )
                    .subject,
                ExactDeclarationTextRange.parse(1, 2).value(),
                ValueRole.ExpressionResult,
            )
            .value()

    private fun admission(peer: RelationPagingFixture, target: ValueSite): QueryImpactPeerSiteAdmission {
        val range = target.range
        val request =
            ValueSiteRevalidationRequest.create(peer.selector, range, ValueSiteRoleClaim.ExpressionResult, peer.budget)
                .value()
        val selected =
            QueryImpactRequestedSite.admit(
                    request,
                    RevalidatedValueSite.fromCompiler(request, target).value(),
                    RelationWorkCount.parse(1).value(),
                )
                .value()
        val receipt =
            QueryImpactPeerAcquisitionReceipt.fromCompletedRead(
                    peer.authority,
                    peer.budget,
                    RelationWorkCount.parse(3).value(),
                    QueryImpactPeerElapsedNanos.parse(11).value(),
                )
                .value()
        return QueryImpactPeerSiteAdmission.admit(selected, receipt).value()
    }

    private fun id(raw: String) = ModelIdentifier.parse(raw).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
