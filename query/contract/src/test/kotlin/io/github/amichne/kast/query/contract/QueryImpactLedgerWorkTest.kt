package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFlowWorkReceipt
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

class QueryImpactLedgerWorkTest {
    @Test
    fun `independent producers avoid repeated whole-ledger enumeration`(reporter: TestReporter) {
        assertLinearInputWork(independentProducers(128), reporter)
    }

    @Test
    fun `one producer with many branches avoids repeated whole-ledger enumeration`(reporter: TestReporter) {
        assertLinearInputWork(branches(128), reporter)
    }

    @Test
    fun `only exact prefixes share their branch inventory`() {
        val broad = branches(128)
        val broadIndex = QueryImpactLedgerPaths(broad.paths)
        val root = broadIndex.at(broad.paths.first(), 0)
        broad.paths.forEach { path -> assertSame(root, broadIndex.at(path, 0)) }
        assertEquals(
            129,
            broad.paths.flatMap { path -> (0..path.steps.size).map { broadIndex.at(path, it) } }.toSet().size,
        )
        assertNotSame(broadIndex.at(broad.paths.first(), 1), broadIndex.at(broad.paths.last(), 1))
        val independent = independentProducers(128)
        val independentIndex = QueryImpactLedgerPaths(independent.paths)
        val prefixes = independent.paths.flatMap { path -> (0..path.steps.size).map { independentIndex.at(path, it) } }
        assertEquals(384, prefixes.toSet().size)
    }

    @Test
    fun `wide prefix cannot lose a branch or hide an unrelated receipt`() {
        val input = branches(128)
        val absent = site(input.fixture, 195, ValueRole.LocalRead)
        val receipts = input.receipts + (absent to input.observations.first().receipts)
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            input.ledger(paths = input.paths.dropLast(1), receipts = receipts),
        )
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNACCOUNTED_NATIVE_READ),
            input.ledger(receipts = receipts),
        )
    }

    @Test
    fun `receipt lookup preserves its expansion domain`() {
        val input = independentProducers(4)
        val source = input.seeds.first()
        val wrong =
            ValueFlowWorkReceipt.rejected(
                ValueFlowRequest(source, input.fixture.domain.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                RelationWorkCount.parse(1).value(),
            )
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.DOMAIN_MISMATCH),
            input.ledger(receipts = input.receipts + (source to listOf(wrong))),
        )
    }

    @Test
    fun `a conserved prefix cannot admit a later unproven compiler step`() {
        val f = QueryImpactLedgerFixture()
        val target = site(f, 90, ValueRole.LocalBinding)
        val observation = f.observe(target, emptyList())
        val path =
            QueryImpactPath.fromEvidence(
                    f.producer,
                    listOf(QueryImpactStep.Compiler(edge(f.producer, target, ValueTransferKind.LOCAL_BINDING))),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(observation).value(),
                )
                .value()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_COMPILER_STEP),
            f.ledger(f.paths + path, f.observations + observation),
        )
    }

    @Test
    fun `a conserved prefix cannot admit a later unproven terminal`() {
        val f = QueryImpactLedgerFixture()
        val path =
            QueryImpactPath.fromEvidence(
                    f.producer,
                    f.paths.first().steps,
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(
                        ValueFlowObligation(f.destination, ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
                    ),
                )
                .value()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_TERMINAL),
            f.ledger(f.paths + path),
        )
    }

    private fun assertLinearInputWork(input: LedgerInput, reporter: TestReporter) {
        val seeds = CountedList(input.seeds)
        val observations = CountedList(input.observations)
        val paths = CountedList(input.paths)
        val ledger = input.ledger(seeds, observations, paths).value()
        assertEquals(QueryImpactClosure.Discharged, ledger.closure)
        assertEquals(input.seeds, ledger.seeds)
        assertEquals(input.paths, ledger.paths)
        assertEquals(input.observations, ledger.observations)
        assertEquals(input.receipts, ledger.readReceipts)
        input.observations.forEach { observation ->
            assertSame(observation.receipts, ledger.readReceipts.getValue(observation.source))
        }
        val visits =
            "seed visits=${seeds.visits}, path visits=${paths.visits}, observation visits=${observations.visits}"
        reporter.publishEntry("ledger-input-visits", visits)
        assertTrue(seeds.visits <= 8 * (input.seeds.size + input.paths.size), visits)
        assertTrue(paths.visits <= 16 * input.paths.size, visits)
        assertTrue(observations.visits <= 8 * input.observations.size, visits)
    }

    private fun independentProducers(count: Int): LedgerInput {
        val f = QueryImpactLedgerFixture()
        val seeds = List(count) { site(f, 60 + it, ValueRole.ExpressionResult) }
        val producers = seeds.map { producer(f, it) }
        val edges = seeds.map { edge(it, f.first, ValueTransferKind.LOCAL_BINDING) }
        val observations =
            seeds.zip(edges).map { (seed, transfer) -> f.observe(seed, listOf(transfer)) } +
                listOf(f.observations[1], f.observations.last())
        val paths =
            seeds.zip(edges).map { (seed, transfer) ->
                QueryImpactPath.fromEvidence(
                        seed,
                        listOf(transfer, f.edges[2]).map(QueryImpactStep::Compiler),
                        QueryImpactRepresentation.NotModeled,
                        f.terminal,
                    )
                    .value()
            }
        return LedgerInput(f, seeds, producers, observations, paths)
    }

    private fun branches(count: Int): LedgerInput {
        val f = QueryImpactLedgerFixture(results = count, returnedBytes = 1_000_000_000)
        val targets = List(count) { site(f, 60 + it, ValueRole.LocalBinding) }
        val edges = targets.map { edge(f.producer, it, ValueTransferKind.LOCAL_BINDING) }
        val ends = targets.map { f.observe(it, emptyList()) }
        val paths =
            edges.zip(ends).map { (transfer, end) ->
                QueryImpactPath.fromEvidence(
                        f.producer,
                        listOf(QueryImpactStep.Compiler(transfer)),
                        QueryImpactRepresentation.NotModeled,
                        QueryImpactTerminal.SupportedDomainEnd.admit(end).value(),
                    )
                    .value()
            }
        return LedgerInput(
            f,
            listOf(f.producer),
            listOf(f.producerWitness),
            listOf(f.observe(f.producer, edges)) + ends,
            paths,
        )
    }

    private fun site(f: QueryImpactLedgerFixture, offset: Int, role: ValueRole): ValueSite =
        ValueSite.fromCompiler(f.owner, ExactDeclarationTextRange.parse(offset, offset + 1).value(), role).value()

    private fun producer(f: QueryImpactLedgerFixture, site: ValueSite): QueryImpactProducer =
        QueryImpactProducer.admit(site, ValueInvocation.fromCompiler(f.owner, site.range, f.owner).value()).value()

    private fun edge(source: ValueSite, target: ValueSite, kind: ValueTransferKind): ValueTransfer =
        ValueTransfer.fromCompiler(source, target, kind).value()

    private class CountedList<T>(private val values: List<T>) : AbstractList<T>() {
        var visits = 0
            private set

        override val size: Int
            get() = values.size

        override fun get(index: Int): T {
            visits++
            return values[index]
        }
    }

    private class LedgerInput(
        val fixture: QueryImpactLedgerFixture,
        val seeds: List<ValueSite>,
        val producers: List<QueryImpactProducer>,
        val observations: List<ValueFlowStep>,
        val paths: List<QueryImpactPath>,
    ) {
        val receipts = observations.associate { it.source to it.receipts }

        fun ledger(
            seeds: List<ValueSite> = this.seeds,
            observations: List<ValueFlowStep> = this.observations,
            paths: List<QueryImpactPath> = this.paths,
            receipts: Map<ValueSite, List<ValueFlowWorkReceipt>> = this.receipts,
        ) =
            QueryImpactLedger.fromEvidence(
                seeds,
                fixture.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                observations,
                paths,
                originalProducers = producers,
                readReceipts = receipts,
            )
    }
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
