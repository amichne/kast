package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
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
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class QueryImpactModelConservationTest {
    @Test
    fun `every applicable origin model requires its own retained route`() {
        val f = QueryImpactLedgerTest.Fixture()
        val origins = origins(f)
        val observation = f.observe(f.producer, emptyList())
        val terminal = QueryImpactTerminal.SupportedDomainEnd.admit(observation).value()
        val paths = origins.map { rule ->
            QueryImpactPath.fromEvidence(
                    f.producer,
                    emptyList(),
                    QueryImpactRepresentation.Present(
                        RepresentationEvidence.origin(f.producer, f.producerWitness.invocation, rule).value()
                    ),
                    terminal,
                )
                .value()
        }
        fun ledger(retained: List<QueryImpactPath>) =
            QueryImpactLedger.fromEvidence(
                listOf(f.producer),
                f.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                origins,
                emptyList(),
                listOf(observation),
                retained,
                originalProducers = listOf(f.producerWitness),
            )
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH), ledger(paths.take(1)))
        assertEquals(QueryImpactClosure.Discharged, ledger(paths).value().closure)
    }

    @Test
    fun `native branches cannot borrow conservation from another origin route`() {
        val f = QueryImpactLedgerTest.Fixture()
        val rules = origins(f)
        val paths = rules.flatMap { rule -> nativePaths(f, rule) }
        fun ledger(retained: List<QueryImpactPath>) =
            QueryImpactLedger.fromEvidence(
                listOf(f.producer),
                f.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                rules,
                emptyList(),
                f.observations,
                retained,
                originalProducers = listOf(f.producerWitness),
            )
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH), ledger(listOf(paths[0], paths[3])))
        assertEquals(QueryImpactClosure.Discharged, ledger(paths).value().closure)
    }

    private fun nativePaths(f: QueryImpactLedgerTest.Fixture, rule: RepresentationRule.Origin) =
        f.paths.map { route ->
            val evidence =
                route.steps.fold(
                    RepresentationEvidence.origin(f.producer, f.producerWitness.invocation, rule).value()
                ) { current, step ->
                    current.transfer((step as QueryImpactStep.Compiler).transfer).value()
                }
            QueryImpactPath.fromEvidence(
                    f.producer,
                    route.steps,
                    QueryImpactRepresentation.Present(evidence),
                    route.terminal,
                )
                .value()
        }

    @Test
    fun `every applicable boundary alternative retains its obligations`() {
        val f = QueryImpactLedgerTest.Fixture()
        val models = listOf(boundary(f.producer, "first"), boundary(f.producer, "second"))
        val observation = f.observe(f.producer, emptyList())
        val paths = models.map { model ->
            path(f, QueryImpactTerminal.ModeledTerminal(BoundaryArrival.terminal(model.source, model).value()))
        }
        fun ledger(retained: List<QueryImpactPath>) = boundaryLedger(f, models, retained, listOf(observation))
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH), ledger(paths.take(1)))
        assertEquals(
            setOf(QueryImpactRequiredObligation.BOUNDARY),
            (ledger(paths).value().closure as QueryImpactClosure.Unresolved).required,
        )
    }

    @Test
    fun `origin models for another exact callable stay retained without creating routes`() {
        val f = QueryImpactLedgerTest.Fixture()
        val unused = origins(f, otherDeclaration(f))
        val observation = f.observe(f.producer, emptyList())
        val retained = path(f, QueryImpactTerminal.SupportedDomainEnd.admit(observation).value())
        val ledger =
            QueryImpactLedger.fromEvidence(
                    listOf(f.producer),
                    f.domain.boundary,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    unused,
                    emptyList(),
                    listOf(observation),
                    listOf(retained),
                    originalProducers = listOf(f.producerWitness),
                )
                .value()
        assertEquals(QueryImpactClosure.Discharged, ledger.closure)
        assertEquals(unused, ledger.representationModels)
    }

    @Test
    fun `unvisited boundary model stays retained without creating a route`() {
        val f = QueryImpactLedgerTest.Fixture()
        val unused = boundary(f.destination, "unused")
        val observation = f.observe(f.producer, emptyList())
        val retained = path(f, QueryImpactTerminal.SupportedDomainEnd.admit(observation).value())
        val ledger = boundaryLedger(f, listOf(unused), listOf(retained), listOf(observation)).value()
        assertEquals(QueryImpactClosure.Discharged, ledger.closure)
        assertEquals(listOf(unused), ledger.boundaryModels)
    }

    @Test
    fun `execution cutoff retains incompleteness before applying boundary models`() {
        val f = QueryImpactLedgerTest.Fixture()
        val model = boundary(f.producer, "pending")
        val cutoff =
            QueryImpactExecutionStop.CheckpointCapacity.admit(
                    f.producer,
                    RelationByteCount.parse(11).value(),
                    RelationByteLimit.parse(10).value(),
                )
                .value()
        val retained = path(f, QueryImpactTerminal.Unresolved.ExecutionStop(cutoff))
        val ledger = boundaryLedger(f, listOf(model), listOf(retained), emptyList()).value()
        assertEquals(
            setOf(QueryImpactRequiredObligation.EXECUTION_BOUNDARY),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
    }

    private fun path(f: QueryImpactLedgerTest.Fixture, terminal: QueryImpactTerminal) =
        QueryImpactPath.fromEvidence(f.producer, emptyList(), QueryImpactRepresentation.NotModeled, terminal).value()

    private fun boundaryLedger(
        f: QueryImpactLedgerTest.Fixture,
        models: List<BoundaryModel>,
        paths: List<QueryImpactPath>,
        observations: List<io.github.amichne.kast.relation.contract.ValueFlowStep>,
    ) =
        QueryImpactLedger.fromEvidence(
            listOf(f.producer),
            f.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            models,
            observations,
            paths,
            originalProducers = listOf(f.producerWitness),
        )

    private fun boundary(site: ValueSite, name: String): BoundaryModel.Terminal {
        val version = ModelVersion.parse(1).value()
        val position =
            BoundaryPosition.at(
                site,
                BoundaryKind.PERSISTENCE,
                BoundaryContractIdentity(id("storage"), version),
                id("slot"),
            )
        val reference = ModelRuleReference(ContractModelIdentity(id("storage"), version, id("review:914")), id(name))
        return BoundaryModel.Terminal.admit(
                reference,
                position.reference,
                position,
                BoundaryTerminalMeaning.REVIEWED_RETENTION,
            )
            .value()
    }

    private fun origins(
        f: QueryImpactLedgerTest.Fixture,
        declaration: CompilerGroundedSymbolEvidence = f.evidence,
    ): List<RepresentationRule.Origin> {
        val owner = RelationEndpoint.resolve(f.lease, f.scope, declaration).value()
        val identity = ContractModelIdentity(id("representation"), ModelVersion.parse(1).value(), id("review:913"))
        val vocabulary = RepresentationDomain.admit(identity, listOf(id("A"), id("B"))).value()
        val output =
            ExactModelCallablePosition.admit(
                    ModelCallableReference(
                        owner.lease.identity,
                        owner.compilerIdentity,
                        owner.file,
                        owner.range,
                        ModelValuePosition.Result,
                    ),
                    RevalidatedRelationEndpoint.validate(owner, declaration).value(),
                )
                .value()
        return listOf("A", "B").map { state ->
            RepresentationRule.Origin.admit(
                    ModelRuleReference(identity, id("origin-$state")),
                    output,
                    vocabulary.state(id(state)).value(),
                )
                .value()
        }
    }

    private fun otherDeclaration(f: QueryImpactLedgerTest.Fixture) =
        CompilerGroundedSymbolEvidence.fromBoundary(
                f.file,
                50,
                90,
                "other",
                "fixture.other",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("fixture.other", null, emptyList(), emptyList(), 0).value(),
            )
            .value()

    private fun id(raw: String) = ModelIdentifier.parse(raw).value()

    private fun <V, F> Refinement<V, F>.value(): V = assertInstanceOf<Refinement.Refined<V>>(this).value
}
