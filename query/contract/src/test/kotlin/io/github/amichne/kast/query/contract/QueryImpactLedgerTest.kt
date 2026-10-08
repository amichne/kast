package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryImpactLedgerTest {
    @Test
    fun `a rejected resume must conserve every transfer and obligation from its suspended prefix`() {
        val f = QueryImpactLedgerFixture()
        val obligation = ValueFlowObligation(f.first, ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        val suspended =
            ValueFlowStep.fromCompiler(
                    f.first,
                    listOf(f.edges[2]),
                    listOf(obligation),
                    ValueFlowTerminal.ResourceSuspended,
                    f.domain,
                    RelationWorkCount.parse(3).value(),
                )
                .value()
        val rejection =
            QueryImpactReadRejection.Native(
                f.first,
                f.domain.boundary,
                ValueFlowRejection.AUTHORITY_MOVED,
                RelationWorkCount.parse(1).value(),
            )
        val prefix = listOf(QueryImpactStep.Compiler(f.edges[0]))
        fun path(steps: List<QueryImpactStep>, terminal: QueryImpactTerminal) =
            QueryImpactPath.fromEvidence(
                    f.producer,
                    steps,
                    QueryImpactRepresentation.NotModeled,
                    terminal,
                )
                .value()
        val rejected = path(prefix, QueryImpactTerminal.Unresolved.ReadRejected(rejection))
        val transferred = path(prefix + QueryImpactStep.Compiler(f.edges[2]), f.terminal)
        val qualified = path(prefix, QueryImpactTerminal.Unresolved.Flow(obligation))
        fun ledger(paths: List<QueryImpactPath>) =
            QueryImpactLedger.fromEvidence(
                listOf(f.producer),
                f.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                listOf(f.observe(f.producer, listOf(f.edges[0])), suspended) +
                    if (transferred in paths) listOf(f.observations.last()) else emptyList(),
                paths,
                listOf(rejection),
                listOf(f.producerWitness),
            )
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH), ledger(listOf(rejected)))
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            ledger(listOf(rejected, transferred)),
        )
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH), ledger(listOf(rejected, qualified)))
        assertInstanceOf(Refinement.Refined::class.java, ledger(listOf(rejected, transferred, qualified)))
    }

    @Test
    fun `rejected read retains exact cause and cannot discharge native coverage`() {
        val fixture = QueryImpactLedgerFixture()
        val rejection =
            QueryImpactReadRejection.Contract(
                fixture.producer,
                fixture.domain.boundary,
                ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED,
                RelationWorkCount.parse(3).value(),
            )
        val path =
            QueryImpactPath.fromEvidence(
                    fixture.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.ReadRejected(rejection),
                )
                .value()
        val ledger =
            QueryImpactLedger.fromEvidence(
                    listOf(fixture.producer),
                    fixture.domain.boundary,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(path),
                    listOf(rejection),
                    listOf(fixture.producerWitness),
                )
                .value()
        assertSame(rejection, ledger.readRejections.single())
        assertEquals(
            setOf(QueryImpactRequiredObligation.NATIVE_FLOW),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_TERMINAL),
            QueryImpactLedger.fromEvidence(
                listOf(fixture.producer),
                fixture.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(path),
                originalProducers = listOf(fixture.producerWitness),
            ),
        )
    }

    @Test
    fun `capacity cutoff requires exceeded grant and preserves execution obligation`() {
        val fixture = QueryImpactLedgerFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactExecutionStopFailure.CAPACITY_NOT_EXCEEDED),
            QueryImpactExecutionStop.CheckpointCapacity.admit(
                fixture.producer,
                RelationByteCount.parse(10).value(),
                RelationByteLimit.parse(10).value(),
            ),
        )
        val stop =
            QueryImpactExecutionStop.CheckpointCapacity.admit(
                    fixture.producer,
                    RelationByteCount.parse(11).value(),
                    RelationByteLimit.parse(10).value(),
                )
                .value()
        val path =
            QueryImpactPath.fromEvidence(
                    fixture.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.ExecutionStop(stop),
                )
                .value()
        val ledger = fixture.ledger(listOf(path), emptyList()).value()
        assertEquals(
            setOf(QueryImpactRequiredObligation.EXECUTION_BOUNDARY),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
    }

    @Test
    fun `cycle cutoff proves exact repeated route and cannot attach to a different path`() {
        val fixture = QueryImpactLedgerFixture()
        val first = fixture.edges.first()
        val back =
            ValueTransfer.fromCompiler(first.target, fixture.producer, ValueTransferKind.BRANCH_ALTERNATIVE).value()
        val prefix = listOf(QueryImpactStep.Compiler(first), QueryImpactStep.Compiler(back))
        assertEquals(
            Refinement.Rejected(QueryImpactExecutionStopFailure.NO_REPEATED_SITE),
            QueryImpactExecutionStop.Cycle.admit(fixture.producer, prefix.take(1)),
        )
        val stop = QueryImpactExecutionStop.Cycle.admit(fixture.producer, prefix).value()
        assertEquals(0, stop.repeatedAt)
        assertEquals(
            Refinement.Rejected(QueryImpactPathFailure.DISCONNECTED_STEP),
            QueryImpactPath.fromEvidence(
                fixture.producer,
                emptyList(),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.Unresolved.ExecutionStop(stop),
            ),
        )
        val path =
            QueryImpactPath.fromEvidence(
                    fixture.producer,
                    prefix,
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.ExecutionStop(stop),
                )
                .value()
        val ledger =
            fixture
                .ledger(
                    listOf(path),
                    listOf(
                        fixture.observe(fixture.producer, listOf(first)),
                        fixture.observe(first.target, listOf(back)),
                    ),
                )
                .value()
        assertEquals(
            setOf(QueryImpactRequiredObligation.EXECUTION_BOUNDARY),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
    }

    @Test
    fun `observation outside retained routes cannot disappear from accounting`() {
        val fixture = QueryImpactLedgerFixture()
        val unvisited =
            ValueSite.fromCompiler(fixture.owner, ExactDeclarationTextRange.parse(90, 91).value(), ValueRole.LocalRead)
                .value()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNACCOUNTED_NATIVE_READ),
            fixture.ledger(fixture.paths, fixture.observations + fixture.observe(unvisited, emptyList())),
        )
    }

    @Test
    fun `site-only structural evidence cannot establish full producer identity`() {
        val fixture = QueryImpactLedgerFixture()
        val ledger =
            QueryImpactLedger.fromEvidence(
                    listOf(fixture.producer),
                    fixture.domain.boundary,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    emptyList(),
                    emptyList(),
                    fixture.observations,
                    fixture.paths,
                )
                .value()
        assertEquals(
            setOf(QueryImpactRequiredObligation.PRODUCER_IDENTITY),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
        assertInstanceOf(QueryImpactProducerEvidence.SiteOnly::class.java, ledger.producerEvidence.single())
        val retained = fixture.ledger(fixture.paths).value()
        assertSame(
            fixture.producerWitness,
            (retained.producerEvidence.single() as QueryImpactProducerEvidence.Invocation).producer,
        )
    }

    @Test
    fun `all branches to shared destination retain separate path accounting`() {
        val fixture = QueryImpactLedgerFixture()
        val ledger = fixture.ledger(fixture.paths).value()
        assertEquals(QueryImpactClosure.Discharged, ledger.closure)
        assertEquals(2, ledger.paths.size)
        assertEquals(1, ledger.paths.map { it.destination }.distinct().size)
        assertEquals(2, ledger.paths.map { it.steps }.distinct().size)
    }

    @Test
    fun `dropping one route cannot establish complete accounting`() {
        val fixture = QueryImpactLedgerFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            fixture.ledger(fixture.paths.take(1)),
        )
    }

    @Test
    fun `missing seed and native observation remain finite failures`() {
        val fixture = QueryImpactLedgerFixture()
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_SEED_PATH), fixture.ledger(emptyList()))
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_NATIVE_OBSERVATION),
            fixture.ledger(fixture.paths, fixture.observations.dropLast(1)),
        )
    }

    @Test
    fun `native obligation cannot disappear or produce discharged closure`() {
        val fixture = QueryImpactLedgerFixture()
        val obligation = ValueFlowObligation(fixture.producer, ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW)
        val native = fixture.observe(fixture.producer, emptyList(), listOf(obligation))
        val path =
            QueryImpactPath.fromEvidence(
                    fixture.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .value()
        val ledger = fixture.ledger(listOf(path), listOf(native)).value()
        assertEquals(
            setOf(QueryImpactRequiredObligation.NATIVE_FLOW),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
    }
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
