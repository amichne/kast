package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryTerminalMeaning
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelCallableReference
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactAccountingProjectionTest {
    @Test
    fun `ordinary rows and connectivity only paths report distinct explicit accounting`() {
        assertEquals(
            ImpactAccountingDocument.NotApplicable,
            QueryRows.Symbols.of(emptyList()).impactAccountingDocument().refined(),
        )
        val fixture = Fixture()
        val accounting = QueryRows.ValuePaths.of(fixture.ledger.paths).impactAccountingDocument().refined()
        assertEquals(ImpactAccountingDocument.EvidenceOnly(count(2)), accounting)
    }

    @Test
    fun `investigated pages preserve original ledger counts exact seeds model references and closure`() {
        val fixture = Fixture()
        val full = fixture.rows.impactAccountingDocument().refined() as ImpactAccountingDocument.Investigated
        assertEquals(2L, full.pagePathCount.value)
        assertEquals(2L, full.originalObservationCount.value)
        assertEquals(2L, full.originalPathCount.value)
        assertEquals(fixture.ledger.seeds.map { it.impactDocument().refined() }, full.seeds.values)
        assertEquals(ImpactRequestedBoundaryDocument.Workspace, full.requestedDomain)
        assertEquals(ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1, full.semantics)
        assertEquals(
            listOf(fixture.origin.reference.impactDocument().refined()),
            full.representationModelReferences.values,
        )
        assertEquals(listOf(fixture.boundary.reference.impactDocument().refined()), full.boundaryModelReferences.values)
        assertEquals(ImpactAccountingStatusDocument.Conserved, full.status)
        val page =
            fixture.rows.selectRows(listOf(1)).refined().impactAccountingDocument().refined()
                as ImpactAccountingDocument.Investigated
        assertEquals(1L, page.pagePathCount.value)
        assertEquals(full.seeds, page.seeds)
        assertEquals(full.originalObservationCount, page.originalObservationCount)
        assertEquals(full.originalPathCount, page.originalPathCount)
        assertEquals(ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged), page.status)
    }

    @Test
    fun `selected page retains unresolved obligation from original investigation`() {
        val fixture = Fixture(unresolved = true)
        val full = fixture.rows.impactAccountingDocument().refined() as ImpactAccountingDocument.Investigated
        assertEquals(
            listOf(ImpactRequiredObligationDocument.NATIVE_FLOW),
            (full.status as ImpactAccountingStatusDocument.Unresolved).required.values,
        )
        val page =
            fixture.rows.selectRows(listOf(1)).refined().impactAccountingDocument().refined()
                as ImpactAccountingDocument.Investigated
        assertEquals(
            listOf(ImpactRequiredObligationDocument.NATIVE_FLOW),
            ((page.status as ImpactAccountingStatusDocument.SelectedSubset).originalClosure
                    as ImpactClosureDocument.Unresolved)
                .required
                .values,
        )
    }

    @Test
    fun `rejected seed read counts remain distinct from successful native observations`() {
        val fixture = Fixture()
        val seed = fixture.ledger.seeds.first()
        val rejection =
            QueryImpactReadRejection.Native(
                seed,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
                ValueFlowRejection.NATIVE_UNAVAILABLE,
                RelationWorkCount.parse(3).refined(),
            )
        val path =
            QueryImpactPath.fromEvidence(
                    seed,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.ReadRejected(rejection),
                )
                .refined()
        val ledger =
            QueryImpactLedger.fromEvidence(
                    listOf(seed),
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(path),
                    listOf(rejection),
                    listOf(fixture.producers.first()),
                )
                .refined()
        val accounting =
            QueryRows.ValuePaths.fromInvestigation(ledger).refined().impactAccountingDocument().refined()
                as ImpactAccountingDocument.Investigated
        assertEquals(0L, accounting.originalObservationCount.value)
        assertEquals(1L, accounting.originalReadRejectionCount.value)
        assertEquals(1L, accounting.originalPathCount.value)
        assertEquals(
            listOf(ImpactRequiredObligationDocument.NATIVE_FLOW),
            (accounting.status as ImpactAccountingStatusDocument.Unresolved).required.values,
        )
    }

    @Test
    fun `explicit requested directory and source sets survive accounting and rejected read projection`() {
        val explicit =
            RelationSearchBoundary.Explicit(
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse("src/main").refined(),
                    SymbolDiscoveryContainment.DESCENDANTS,
                ),
                SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("main").refined())).refined(),
            )
        val fixture = Fixture(requestedDomain = explicit)
        val accounting = fixture.rows.impactAccountingDocument().refined() as ImpactAccountingDocument.Investigated
        val projected = accounting.requestedDomain as ImpactRequestedBoundaryDocument.SourceDomain
        assertEquals(QuerySemanticScopeDocument.Workspace, projected.domain.scope)
        assertEquals(
            QueryDirectoryScopeDocument(ProtocolText.parse("src/main").refined(), QueryContainmentDocument.DESCENDANTS),
            projected.domain.directory,
        )
        assertEquals(
            QueryDiscoverySourceSetsDocument.Exact(
                BoundedProtocolList.create(listOf(ProtocolText.parse("main").refined())).refined()
            ),
            projected.domain.sourceSets,
        )
        val rejection =
            QueryImpactReadRejection.Native(
                    fixture.ledger.seeds.first(),
                    explicit,
                    ValueFlowRejection.OUTSIDE_DOMAIN,
                    RelationWorkCount.parse(1).refined(),
                )
                .impactDocument()
                .refined() as ImpactReadRejectionDocument.Native
        assertEquals(projected, rejection.domain)
    }

    @Test
    fun `model witness section retains complete original reviewed rules in stable order`() {
        val fixture = Fixture()
        val view = QueryImpactWitnessView.create(fixture.ledger, QueryImpactWitnessSection.MODELS, 0, 2).refined()
        val projected = QueryRows.ImpactWitness.of(view).projectWitnessItems() as QueryProjection.Projected
        val records = projected.values.map { (it as QueryResultItemDocument.ImpactWitness).item }
        assertEquals(listOf(0L, 1L), records.map { it.ordinal.value })
        val representation = records[0].witness as ImpactWitnessDocument.RepresentationModel
        val boundary = records[1].witness as ImpactWitnessDocument.BoundaryModel
        assertEquals(fixture.origin.reference.impactDocument().refined(), representation.reference)
        assertEquals(fixture.origin.impactDocument().refined(), representation.rule)
        assertEquals(fixture.boundary.reference.impactDocument().refined(), boundary.reference)
        assertEquals(fixture.boundary.impactDocument().refined(), boundary.rule)
        val accounting =
            QueryRows.ImpactWitness.of(view).impactAccountingDocument().refined()
                as ImpactAccountingDocument.Investigated
        assertEquals(ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged), accounting.status)
        assertEquals(2L, accounting.originalPathCount.value)
    }

    private inner class Fixture(
        unresolved: Boolean = false,
        val requestedDomain: RelationSearchBoundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
    ) {
        private val fixture = RelationPagingFixture.published()
        private val domain =
            RelationRequest.start(
                fixture.selector,
                RelationMeaning.References,
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(10).refined(),
                        WorkUnitLimit.parse(100).refined(),
                        ElapsedTimeLimitMillis.parse(1000).refined(),
                    ),
                    RelationByteLimit.parse(100000).refined(),
                ),
                requestedDomain,
            )
        private val seeds =
            listOf(1, 3).map {
                ValueSite.fromCompiler(
                        domain.subject,
                        ExactDeclarationTextRange.parse(it, it + 1).refined(),
                        ValueRole.ExpressionResult,
                    )
                    .refined()
            }
        val producers = seeds.map { site ->
            QueryImpactProducer.admit(
                    site,
                    ValueInvocation.fromCompiler(site.enclosing, site.range, site.enclosing).refined(),
                )
                .refined()
        }
        private val model =
            ContractModelIdentity(id("representation"), ModelVersion.parse(2).refined(), id("review:913"))
        private val state =
            RepresentationDomain.admit(model, listOf(id("HIPED"))).refined().state(id("HIPED")).refined()
        val origin =
            RepresentationRule.Origin.admit(
                    ModelRuleReference(model, id("origin")),
                    ExactModelCallablePosition.admit(
                            ModelCallableReference(
                                domain.subject.lease.identity,
                                domain.subject.compilerIdentity,
                                domain.subject.file,
                                domain.subject.range,
                                ModelValuePosition.Result,
                            ),
                            RevalidatedRelationEndpoint.validate(
                                    domain.subject,
                                    CompilerGroundedSymbolEvidence.fromBoundary(
                                            domain.subject.file,
                                            domain.subject.range.startInclusive,
                                            domain.subject.range.endExclusive,
                                            domain.subject.name.value,
                                            (domain.subject.qualifiedIdentity
                                                    as ExactDeclarationQualifiedIdentity.Available)
                                                .value,
                                            domain.subject.kind,
                                            domain.subject.signature,
                                        )
                                        .refined(),
                                )
                                .refined(),
                        )
                        .refined(),
                    state,
                )
                .refined()
        private val position =
            BoundaryPosition.at(
                seeds.first(),
                BoundaryKind.SERIALIZATION,
                BoundaryContractIdentity(id("payload"), ModelVersion.parse(1).refined()),
                id("ciphertext"),
            )
        val boundary =
            BoundaryModel.Terminal.admit(
                    ModelRuleReference(model, id("sink")),
                    position.reference,
                    position,
                    BoundaryTerminalMeaning.REVIEWED_DISPOSAL,
                )
                .refined()
        private val observations = seeds.mapIndexed { index, site ->
            val obligations =
                if (unresolved && index == 0) listOf(ValueFlowObligation(site, ValueFlowUnsupportedCause.EXTERNAL_CALL))
                else emptyList()
            ValueFlowStep.fromCompiler(
                    site,
                    emptyList(),
                    obligations,
                    if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted
                    else ValueFlowTerminal.Unresolved,
                    domain,
                    RelationWorkCount.parse(1).refined(),
                )
                .refined()
        }
        private val paths = observations.flatMap { observation ->
            val producer = producers.single { it.site == observation.source }
            val representation =
                QueryImpactRepresentation.Present(
                    RepresentationEvidence.origin(producer.site, producer.invocation, origin).refined()
                )
            terminals(observation).map { terminal ->
                QueryImpactPath.fromEvidence(observation.source, emptyList(), representation, terminal).refined()
            }
        }

        private fun terminals(observation: ValueFlowStep): List<QueryImpactTerminal> {
            val modeled =
                if (observation.source == boundary.source.site) {
                    listOf(
                        QueryImpactTerminal.ModeledTerminal(
                            BoundaryArrival.terminal(boundary.source, boundary).refined()
                        )
                    )
                } else emptyList()
            val obligations = observation.obligations.map(QueryImpactTerminal.Unresolved::Flow)
            return if (modeled.isEmpty() && obligations.isEmpty()) {
                listOf(QueryImpactTerminal.SupportedDomainEnd.admit(observation).refined())
            } else modeled + obligations
        }

        val ledger =
            QueryImpactLedger.fromEvidence(
                    seeds,
                    requestedDomain,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    listOf(origin),
                    listOf(boundary),
                    observations,
                    paths,
                    originalProducers = producers,
                )
                .refined()
        val rows = QueryRows.ValuePaths.fromInvestigation(ledger).refined()
    }

    private fun id(raw: String) = ModelIdentifier.parse(raw).refined()

    private fun count(raw: Long) = QueryDiscoveryCountDocument.parse(raw).refined()

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
