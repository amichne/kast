package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactPeerAcquisitionReceipt
import io.github.amichne.kast.query.contract.QueryImpactPeerBoundary
import io.github.amichne.kast.query.contract.QueryImpactPeerElapsedNanos
import io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.accountingStatus
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryRequiredEvidence
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RepresentationCurrent
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RepresentationUnknownReason
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/** Scripted source reads prove interpreter behavior, not installed compiler behavior. */
class QueryImpactPeerBoundaryExecutionTest {
    @Test
    fun `modeled peer continuation terminates qualified without peer flow`() = runTest {
        val f = QueryImpactExecutionFixture()
        val peer = peerBoundary(f)
        val plan = f.plan(boundaries = listOf(peer.model), peerBoundaries = listOf(peer))
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(
                        f.producer.site,
                        causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL),
                    )
                )
            )
        val attempts = mutableListOf<ValueSite>()
        val port =
            io.github.amichne.kast.relation.contract.ValueFlowCompilerPort { request ->
                attempts += request.source
                native.port.read(request)
            }
        val execution =
            try {
                f.service(port).run(f.request(plan = plan))
            } finally {
                assertEquals(listOf(f.producer.site), attempts, "Peer compiler work is forbidden")
                native.assertConsumed()
            }
        val result = assertInstanceOf<QueryExecutionResult.Qualified>(execution)
        val rows = assertInstanceOf<QueryRows.ValuePaths>(result.result.rows)
        val path = rows.values.single()
        val edge = assertInstanceOf<QueryImpactStep.ModeledBoundary>(path.steps.single())
        val terminal = assertInstanceOf<QueryImpactTerminal.Unresolved.PeerContinuation>(path.terminal)
        assertEquals(peer.model, edge.connection.model)
        assertSame(peer, terminal.boundary)
        assertEquals(peer.target.site, path.destination)
        assertEquals(f.lease.identity, path.producerBasis)
        assertEquals(peer.target.site.basis, path.destinationBasis)
        assertEquals(
            setOf(QueryImpactRequiredObligation.BOUNDARY),
            assertInstanceOf<QueryValuePathAccountingStatus.Unresolved>(rows.accountingStatus).required,
        )
        val ledger = assertInstanceOf<QueryValuePathAccounting.Investigated>(rows.accounting).ledger
        assertEquals(listOf(peer), ledger.peerBoundaries)
        assertEquals(listOf(f.producer.site), ledger.observations.map { it.source })
        assertEquals(listOf(f.producer.site), native.examined)
        assertTrue(ledger.paths.single() === path)
        native.assertConsumed()
    }

    @Test
    fun `peer arrival preserves native sibling and mutable obligation without peer work`() = runTest {
        val f = QueryImpactExecutionFixture()
        val peer = peerBoundary(f)
        val sibling = f.site(20, ValueRole.LocalBinding)
        val transfer = f.edge(f.producer.site, sibling, ValueTransferKind.LOCAL_BINDING)
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(
                        f.producer.site,
                        listOf(transfer),
                        listOf(
                            ValueFlowUnsupportedCause.UNMODELED_CALL,
                            ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW,
                        ),
                    ),
                    ImpactReadExpectation(sibling),
                )
            )
        val plan = f.plan(boundaries = listOf(peer.model), peerBoundaries = listOf(peer))
        val rows = executeSourceOnly(f, native, plan)
        assertEquals(3, rows.values.size)
        assertEquals(1, rows.values.count { it.terminal is QueryImpactTerminal.Unresolved.PeerContinuation })
        assertEquals(1, rows.values.count { it.terminal is QueryImpactTerminal.SupportedDomainEnd })
        val flow = rows.values.map { it.terminal }.filterIsInstance<QueryImpactTerminal.Unresolved.Flow>().single()
        assertEquals(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW, flow.obligation.cause)
        assertEquals(
            setOf(QueryImpactRequiredObligation.BOUNDARY, QueryImpactRequiredObligation.NATIVE_FLOW),
            assertInstanceOf<QueryValuePathAccountingStatus.Unresolved>(rows.accountingStatus).required,
        )
    }

    @Test
    fun `peer persistence retains all reviewed storage requirements`() = runTest {
        val f = QueryImpactExecutionFixture()
        val peer = peerBoundary(f, BoundaryKind.PERSISTENCE)
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(
                        f.producer.site,
                        causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL),
                    )
                )
            )
        val rows =
            executeSourceOnly(
                f,
                native,
                f.plan(boundaries = listOf(peer.model), peerBoundaries = listOf(peer)),
            )
        val connection =
            assertInstanceOf<QueryImpactStep.ModeledBoundary>(rows.values.single().steps.single()).connection
        assertEquals(
            setOf(
                BoundaryRequiredEvidence.RETENTION_POLICY,
                BoundaryRequiredEvidence.DECODING_COMPATIBILITY,
                BoundaryRequiredEvidence.MIGRATION_PROOF,
            ),
            connection.obligations.single().required,
        )
    }

    @Test
    fun `peer hop preserves origin history and leaves unreviewed representation unknown`() = runTest {
        val f = QueryImpactExecutionFixture()
        val peer = peerBoundary(f)
        val identity = ContractModelIdentity(id("encoding"), ModelVersion.parse(1).value(), id("review:913"))
        val vocabulary = RepresentationDomain.admit(identity, listOf(id("ENCRYPTED"))).value()
        val rule =
            RepresentationRule.Origin.admit(
                    ModelRuleReference(identity, id("origin")),
                    f.bind(f.producer.invocation.callable, ModelValuePosition.Result),
                    vocabulary.state(id("ENCRYPTED")).value(),
                )
                .value()
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(
                        f.producer.site,
                        causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL),
                    )
                )
            )
        val rows =
            executeSourceOnly(
                f,
                native,
                f.plan(listOf(rule), listOf(peer.model), peerBoundaries = listOf(peer)),
            )
        val representation = assertInstanceOf<QueryImpactRepresentation.Present>(rows.values.single().representation)
        val branch = representation.evidence.branches.single()
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.BOUNDARY_PRESERVATION_UNPROVEN),
            branch.current,
        )
        assertEquals(rule, assertInstanceOf<RepresentationHistory.Origin>(branch.history.first()).rule)
        assertEquals(
            peer.model.reference,
            assertInstanceOf<RepresentationHistory.BoundaryModel>(branch.history.last()).reference,
        )
        assertEquals(
            setOf(QueryImpactRequiredObligation.BOUNDARY, QueryImpactRequiredObligation.REPRESENTATION_STATE),
            assertInstanceOf<QueryValuePathAccountingStatus.Unresolved>(rows.accountingStatus).required,
        )
    }

    private suspend fun executeSourceOnly(
        f: QueryImpactExecutionFixture,
        native: QueryImpactExecutionFixture.Script,
        plan: io.github.amichne.kast.query.contract.AdmittedQueryPlan,
    ): QueryRows.ValuePaths {
        val attempts = mutableListOf<ValueSite>()
        val port =
            io.github.amichne.kast.relation.contract.ValueFlowCompilerPort { request ->
                attempts += request.source
                native.port.read(request)
            }
        val execution =
            try {
                f.service(port).run(f.request(plan = plan))
            } finally {
                assertEquals(
                    native.examined,
                    attempts,
                    "Every native attempt must consume its exact scripted observation",
                )
                assertTrue(attempts.all { it.enclosing.lease == f.lease }, "Peer compiler work is forbidden")
                native.assertConsumed()
            }
        return assertInstanceOf<QueryRows.ValuePaths>(
            assertInstanceOf<QueryExecutionResult.Qualified>(execution).result.rows
        )
    }

    private fun peerBoundary(
        f: QueryImpactExecutionFixture,
        kind: BoundaryKind = BoundaryKind.SERIALIZATION,
    ): QueryImpactPeerBoundary {
        val admission = peerAdmission(f)
        val site = admission.site
        val version = ModelVersion.parse(1).value()
        val contract = BoundaryContractIdentity(id("wire"), version)
        val source = BoundaryPosition.at(f.producer.site, kind, contract, id("payload"))
        val target = BoundaryPosition.at(site, kind, contract, id("payload"))
        val reference =
            ModelRuleReference(ContractModelIdentity(id("wire-model"), version, id("review:914")), id("peer"))
        val model =
            BoundaryModel.Continuation.admit(
                    reference,
                    source.reference,
                    target.reference,
                    source,
                    target,
                    emptySet(),
                )
                .value()
        return QueryImpactPeerBoundary.admit(f.lease, model, admission).value()
    }

    private fun peerAdmission(f: QueryImpactExecutionFixture): QueryImpactPeerSiteAdmission {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/peer")).value(),
                EvidenceGeneration.parse(19).value(),
            )
        val proof = peerDeclaration(lease)
        val owner = RelationEndpoint.resolve(lease, f.scope, proof).value()
        val site =
            ValueSite.fromCompiler(
                    owner,
                    ExactDeclarationTextRange.parse(50, 51).value(),
                    ValueRole.PropertyAssignment,
                )
                .value()
        val grant =
            f.request().budget.let {
                io.github.amichne.kast.relation.contract.RelationBudget(
                    it.resources,
                    io.github.amichne.kast.relation.contract.RelationByteLimit.parse(it.checkpointBytes.value).value(),
                )
            }
        val request =
            ValueSiteRevalidationRequest.create(
                    SymbolSelector.issue(lease, f.scope, proof),
                    site.range,
                    ValueSiteRoleClaim.PropertyAssignment,
                    grant,
                )
                .value()
        val selection =
            QueryImpactRequestedSite.admit(
                    request,
                    RevalidatedValueSite.fromCompiler(request, site).value(),
                    RelationWorkCount.parse(1).value(),
                )
                .value()
        val receipt =
            QueryImpactPeerAcquisitionReceipt.fromCompletedRead(
                    lease,
                    grant,
                    RelationWorkCount.parse(3).value(),
                    QueryImpactPeerElapsedNanos.parse(10).value(),
                )
                .value()
        return QueryImpactPeerSiteAdmission.admit(selection, receipt).value()
    }

    private fun peerDeclaration(lease: SemanticReadLease): CompilerGroundedSymbolEvidence {
        val file =
            (SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.SYMBOL,
                        "owner",
                        lease,
                        Path.of("/peer/File.kt"),
                        "file:///peer/File.kt",
                        0,
                    )
                    .value()
                    .location as SymbolDiscoveryCandidateLocation.Declaration)
                .file
        return CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                0,
                200,
                "owner",
                "fixture.owner",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function(
                        "fixture.owner",
                        null,
                        emptyList(),
                        emptyList(),
                        0,
                    )
                    .value(),
            )
            .value()
    }

    private fun id(raw: String) = ModelIdentifier.parse(raw).value()
}

private fun <V> Refinement<V, *>.value(): V = (this as Refinement.Refined).value
