package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactContentViewDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactModelFormatDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryImpactPeerAcquisitionReceipt
import io.github.amichne.kast.query.contract.QueryImpactPeerElapsedNanos
import io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Production source admission consumes completed child facts; these cases establish no installed IDE claim. */
class QueryImpactPeerSourceAdmissionTest {
    @Test
    fun `completed exact peer is bound once while all source work stays on source authority`() = runTest {
        val f = Fixture()
        val native = f.source.native()
        val admitted =
            f.document
                .admitImpact(f.source.owner.lease, f.source.references, native, f.source.budget, listOf(f.proof))
                .admitted()
        assertEquals(listOf(7L, 4L, 3L), native.grants)
        assertEquals(listOf(f.source.range(20, 35)), native.positions)
        assertEquals(9L, admitted.examinedWork)
        assertEquals(2, admitted.source.boundaryModels.size)
        val peer = admitted.source.peerBoundaries.single()
        assertSame(f.proof, peer.target)
        assertSame(f.peer.authority, peer.target.acquisition.completedAuthority)
        assertEquals(f.source.owner.lease, peer.model.source.site.enclosing.lease)
        assertEquals(f.proof.site, peer.model.target.site)
        assertEquals(1, admitted.source.producers.size)
    }

    @Test
    fun `missing extra duplicate or stale peer proof rejects before source native work`() = runTest {
        val f = Fixture()
        for ((proofs, code) in
            listOf(
                emptyList<QueryImpactPeerSiteAdmission>() to QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH,
                listOf(f.proof, f.proof) to QueryImpactSourceFailureCode.DUPLICATE_PEER_BOUNDARY,
                listOf(f.proof(1, 2)) to QueryImpactSourceFailureCode.UNDECLARED_PEER_BOUNDARY,
            )) {
            val native = f.source.native()
            val result =
                f.document.admitImpact(f.source.owner.lease, f.source.references, native, f.source.budget, proofs)
            assertEquals(code, result.code())
            assertEquals(emptyList<Long>(), native.grants)
        }
        val stale =
            f.document.copy(
                declarations =
                    bounded(
                        f.document.declarations.values.map { declaration ->
                            if (declaration.declaration.basis is ImpactSemanticBasisDocument.Live) {
                                declaration.copy(
                                    declaration =
                                        declaration.declaration.copy(
                                            basis =
                                                f.basis.copy(epoch = ImpactEvidenceRevisionDocument.parse(2).admitted())
                                        )
                                )
                            } else declaration
                        }
                    )
            )
        val native = f.source.native()
        assertEquals(
            QueryImpactSourceFailureCode.MISSING_DECLARATION,
            stale
                .admitImpact(f.source.owner.lease, f.source.references, native, f.source.budget, listOf(f.proof))
                .code(),
        )
        assertEquals(emptyList<Long>(), native.grants)
    }

    @Test
    fun `peer storage reservation rejects before source seeds consume the original grant`() = runTest {
        val f = Fixture()
        val native = f.source.native()
        val budget = f.source.budget.copy(checkpointBytes = QueryByteLimit.parse(1).admitted())
        assertEquals(
            QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED,
            f.document.admitImpact(f.source.owner.lease, f.source.references, native, budget, listOf(f.proof)).code(),
        )
        assertEquals(emptyList<Long>(), native.grants)
    }

    internal class Fixture {
        val source = QueryImpactBoundarySourceAdmissionTest.Fixture()
        val peer =
            RelationPagingFixture(
                LiveReadAuthorityFixture.create(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/client")).admitted())
            )
        private val reference = (peer.authority.identity as SemanticReadIdentity.Live).reference
        val basis =
            ImpactSemanticBasisDocument.Live(
                source.text(reference.workspaceRoot.value),
                source.text(reference.host.value.toString()),
                ImpactEvidenceRevisionDocument.parse(reference.epoch.value).admitted(),
                ImpactContentViewDocument.SAVED_PSI_COMMITTED,
                ImpactModelFormatDocument.Current,
            )
        private val declaration =
            ImpactDeclarationReferenceDocument(
                basis,
                source.text(peer.selector.file.stableValue),
                range(0, 6),
                source.text(peer.selector.compilerIdentity.value),
            )
        val document =
            source.document().let { original ->
                val model = original.models.values.single() as ImpactModelDocument.Boundary
                val rule = model.rules.values.first() as ImpactBoundaryRuleDocument.Continuation
                original.copy(
                    models =
                        bounded(
                            listOf(
                                model.copy(
                                    rules =
                                        bounded(
                                            listOf(
                                                rule.copy(
                                                    target =
                                                        rule.target.copy(
                                                            site =
                                                                ImpactValueSiteReferenceDocument(
                                                                    declaration,
                                                                    range(0, 1),
                                                                    ImpactValueRoleDocument.PropertyAssignment,
                                                                )
                                                        )
                                                ),
                                                model.rules.values.last(),
                                            )
                                        )
                                )
                            )
                        ),
                    declarations =
                        bounded(original.declarations.values + QueryImpactDeclarationDocument(peer.exact, declaration)),
                )
            }
        val proof = proof(0, 1)

        fun proof(
            start: Int,
            end: Int,
            receiptWork: Long = 3,
            grantWork: Long = source.budget.resources.workUnitLimit.value,
        ): QueryImpactPeerSiteAdmission {
            val grant =
                RelationBudget(
                    source.budget.resources.copy(
                        workUnitLimit = io.github.amichne.kast.kernel.WorkUnitLimit.parse(grantWork).admitted()
                    ),
                    RelationByteLimit.parse(source.budget.checkpointBytes.value).admitted(),
                )
            val request =
                ValueSiteRevalidationRequest.create(
                        peer.selector,
                        source.range(start, end),
                        ValueSiteRoleClaim.PropertyAssignment,
                        grant,
                    )
                    .admitted()
            val site =
                ValueSite.fromCompiler(
                        RelationEndpoint.subject(peer.selector),
                        request.anchor,
                        ValueRole.PropertyAssignment,
                    )
                    .admitted()
            val selection =
                QueryImpactRequestedSite.admit(
                        request,
                        RevalidatedValueSite.fromCompiler(request, site).admitted(),
                        RelationWorkCount.parse(1).admitted(),
                    )
                    .admitted()
            val receipt =
                QueryImpactPeerAcquisitionReceipt.fromCompletedRead(
                        peer.authority,
                        grant,
                        RelationWorkCount.parse(receiptWork).admitted(),
                        QueryImpactPeerElapsedNanos.parse(10).admitted(),
                    )
                    .admitted()
            return QueryImpactPeerSiteAdmission.admit(selection, receipt).admitted()
        }

        private fun range(start: Int, end: Int) =
            ImpactSourceRangeDocument(ProtocolOffset.parse(start).admitted(), ProtocolOffset.parse(end).admitted())
    }

    private fun Refinement<*, QueryRunRejection>.code() =
        (((this as Refinement.Rejected).failure as QueryRunRejection.ImpactSourceRejected).cause
                as QueryImpactSourceFailureDocument.Admission)
            .cause

    companion object {
        private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).admitted()

        private fun <V, F> Refinement<V, F>.admitted(): V =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error("Fixture rejected: $failure")
            }
    }
}
