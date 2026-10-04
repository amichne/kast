package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactClosure
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.LocalBindingReadRemainder
import io.github.amichne.kast.relation.contract.LocalBindingReferenceScan
import io.github.amichne.kast.relation.contract.LocalReferenceKey
import io.github.amichne.kast.relation.contract.LocalReferenceKind
import io.github.amichne.kast.relation.contract.LocalReferenceResolution
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** No PSI, filesystem, clock, process or RPC. Expected paths are authored separately from candidate observations. */
class QueryLocalBindingResumeTest {
    @Test
    fun `draining a binding interrupted inside enumeration recovers the authored paths`() = runTest {
        val large = Scenario().drain(100)
        for (work in listOf(4L, 5L, 6L, 9L)) for (results in listOf(1, 2, 20)) {
            val small = Scenario().drain(work, results)
            assertEquals(large, small, "work=$work results=$results")
        }
    }

    @Test
    fun `native pages respect independent work result and byte grants and rejected-only pages advance`() = runTest {
        for (work in listOf(3L, 4L, 6L, 100L)) for (results in listOf(1, 2, 20)) for (bytes in
            listOf(110000L, 130000L, 1000000L)) {
            assertNativeDrain(nativeGrant(work, results, bytes))
        }
    }

    private fun nativeGrant(work: Long, results: Int, bytes: Long): RelationBudget =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(results).value(),
                WorkUnitLimit.parse(work).value(),
                ElapsedTimeLimitMillis.parse(10000).value(),
            ),
            RelationByteLimit.parse(bytes).value(),
        )

    private suspend fun assertNativeDrain(grant: RelationBudget) {
        val s = Scenario()
        val pages = drainNative(s, grant)
        pages.forEach { page ->
            assertTrue(page.examinedWorkUnits.value <= grant.resources.workUnitLimit.value)
            assertTrue(page.transfers.size <= grant.resources.resultLimit.value)
            assertTrue(page.retainedBytes <= grant.returnedBytes.value)
        }
        assertEquals(listOf(s.d, s.e, s.g), pages.flatMap { it.transfers }.map { it.target })
        assertEquals(
            setOf(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE, ValueFlowUnsupportedCause.NESTED_EXECUTION),
            pages.flatMap { it.obligations }.map { it.cause }.toSet(),
        )
        assertEquals((0..7).toList(), s.confirmations)
        if (grant.resources.workUnitLimit.value == 3L)
            assertTrue(s.reads.filterIsInstance<ValueFlowRead.Suspended>().any { it.step.transfers.isEmpty() })
    }

    private suspend fun drainNative(s: Scenario, grant: RelationBudget): List<ValueFlowStep> {
        var remainder: LocalBindingReadRemainder? = null
        val pages = mutableListOf<ValueFlowStep>()
        repeat(30) {
            val read =
                s.port.read(ValueFlowRequest(s.binding, grant, RelationSearchBoundary.WORKSPACE_EXPANSION, remainder))
            when (read) {
                is ValueFlowRead.Observed -> return pages + read.step
                is ValueFlowRead.Suspended -> {
                    assertTrue(read.remainder.consumed.size > (remainder?.consumed?.size ?: 0))
                    assertTrue(read.step.retainedBytes + read.remainder.retainedBytes <= grant.returnedBytes.value)
                    remainder = read.remainder
                    pages += read.step
                }
                else -> error("Unexpected rejection under viable grant $grant: $read")
            }
        }
        error("Native drain did not terminate")
    }

    private class Scenario {
        val f = QueryImpactExecutionFixture()
        val secondCall = f.call("secondProducer", 21, 29)
        val second = QueryImpactProducer.admit(secondCall.resultSite(), secondCall).value()
        val binding = f.site(30, ValueRole.LocalBinding)
        val d = f.site(60, ValueRole.LocalRead)
        val e = f.site(70, ValueRole.LocalRead)
        val g = f.site(80, ValueRole.LocalRead)
        val producers = listOf(f.producer, second)
        val plan = f.plan(producers = producers)
        // More inputs than the small grant: two rejection-only pages, duplicate destination, three confirmed reads.
        val candidates =
            listOf(
                LocalReferenceResolution.OtherBinding,
                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE),
                LocalReferenceResolution.Transfer(d),
                LocalReferenceResolution.Transfer(d),
                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.NESTED_EXECUTION),
                LocalReferenceResolution.Transfer(e),
                LocalReferenceResolution.OtherBinding,
                LocalReferenceResolution.Transfer(g),
            )
        val nonlocal by lazy {
            f.unorderedScript(
                producers.map {
                    ImpactReadExpectation(it.site, listOf(f.edge(it.site, binding, ValueTransferKind.LOCAL_BINDING)))
                } +
                    listOf(d, e, g).map {
                        ImpactReadExpectation(it, causes = listOf(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION))
                    }
            )
        }
        val confirmations = mutableListOf<Int>()
        val reads = mutableListOf<ValueFlowRead>()
        val grants = mutableListOf<RelationBudget>()
        val port = ValueFlowCompilerPort { request ->
            if (request.source != binding) nonlocal.port.read(request)
            else {
                val domain =
                    RelationRequest.start(
                        binding.enclosing as RelationEndpoint.Resolved,
                        RelationMeaning.References,
                        request.budget,
                        request.boundary,
                    )
                LocalBindingReferenceScan<Int>(
                        request,
                        domain,
                        { visit -> for (i in candidates.indices) if (!visit(i)) break },
                        { i ->
                            val range = ExactDeclarationTextRange.parse(100 + i, 101 + i).value()
                            LocalReferenceKey(
                                range,
                                range,
                                LocalReferenceKind.fromBoundary("fixture.Reference").value(),
                            )
                        },
                        { i ->
                            confirmations += i
                            candidates[i]
                        },
                        {},
                        { 0 },
                    )
                    .read()
                    .also {
                        reads += it
                        grants += request.budget
                    }
            }
        }

        suspend fun drain(work: Long, results: Int = 20): Set<PathFact> {
            val service = f.service(port)
            var checkpoint: QueryCheckpoint? = null
            val output = mutableListOf<QueryImpactPath>()
            var ledger: QueryImpactLedger? = null
            repeat(40) {
                val result =
                    service.run(f.request(work = work, results = results, plan = plan, checkpoint = checkpoint))
                val page =
                    when (result) {
                        is QueryExecutionResult.Complete -> result.result
                        is QueryExecutionResult.Qualified -> result.result
                        else -> error("Unexpected query rejection: $result")
                    }
                val rows = page.rows as QueryRows.ValuePaths
                output += rows.values
                ledger = (rows.accounting as? QueryValuePathAccounting.Investigated)?.ledger ?: ledger
                checkpoint =
                    ((result as? QueryExecutionResult.Qualified)?.continuation as? QueryContinuationState.Resumable)
                        ?.checkpoint
                if (checkpoint == null) {
                    return assertDrained(output, requireNotNull(ledger))
                }
            }
            error("Drain did not terminate")
        }

        private fun assertDrained(output: List<QueryImpactPath>, ledger: QueryImpactLedger): Set<PathFact> {
            val facts = output.map(::fact)
            assertEquals(facts.size, facts.toSet().size, "No branch emitted twice")
            assertEquals(
                oracle(),
                facts.toSet(),
                "Every producer history must retain every confirmed branch and qualification",
            )
            nonlocal.assertConsumed()
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), confirmations)
            assertEquals(
                setOf(d, e, g),
                ledger.observations.single { it.source == binding }.transfers.map { it.target }.toSet(),
            )
            assertEquals(
                setOf(QueryImpactRequiredObligation.NATIVE_FLOW),
                (ledger.closure as QueryImpactClosure.Unresolved).required,
            )
            assertReceipts(ledger)
            return facts.toSet()
        }

        private fun assertReceipts(ledger: QueryImpactLedger) {
            val scan = ledger.observations.single { it.source == binding }
            assertEquals(
                reads.map {
                    (when (it) {
                            is ValueFlowRead.Observed -> it.step
                            is ValueFlowRead.Suspended -> it.step
                            else -> error("Rejected viable grant")
                        })
                        .examinedWorkUnits
                        .value
                },
                scan.receipts.map { it.examinedWorkUnits.value },
            )
            assertEquals(scan.receipts.sumOf { it.examinedWorkUnits.value }, scan.examinedWorkUnits.value)
            assertEquals(grants, scan.receipts.map { it.domain.budget })
            scan.receipts.forEach { receipt ->
                assertTrue(receipt.examinedWorkUnits.value <= receipt.domain.budget.resources.workUnitLimit.value)
                assertTrue(receipt.returnedResults.value <= receipt.domain.budget.resources.resultLimit.value)
                assertTrue(receipt.returnedBytes.value <= receipt.domain.budget.returnedBytes.value)
            }
        }

        fun fact(path: QueryImpactPath): PathFact =
            PathFact(
                path.producer.range.startInclusive,
                path.steps.map { it.target.range.startInclusive },
                ((path.terminal as QueryImpactTerminal.Unresolved.Flow).obligation.cause),
            )

        fun oracle(): Set<PathFact> =
            setOf(
                PathFact(10, listOf(30, 60), ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                PathFact(10, listOf(30, 70), ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                PathFact(10, listOf(30, 80), ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                PathFact(10, listOf(30), ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE),
                PathFact(10, listOf(30), ValueFlowUnsupportedCause.NESTED_EXECUTION),
                PathFact(21, listOf(30, 60), ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                PathFact(21, listOf(30, 70), ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                PathFact(21, listOf(30, 80), ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                PathFact(21, listOf(30), ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE),
                PathFact(21, listOf(30), ValueFlowUnsupportedCause.NESTED_EXECUTION),
            )
    }

    private data class PathFact(val producer: Int, val targets: List<Int>, val cause: ValueFlowUnsupportedCause)
}

private fun <V> Refinement<V, *>.value(): V = (this as Refinement.Refined).value
