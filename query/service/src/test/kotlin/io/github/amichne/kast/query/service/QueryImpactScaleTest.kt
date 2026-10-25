package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactClosure
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactScaleTest {
    @Test fun `256 producers preserve paths receipts and shared proofs`() = runTest { assertScale(256) }

    @Test fun `1024 producers preserve paths receipts and shared proofs`() = runTest { assertScale(1024) }

    @Test fun `4096 producers preserve paths receipts and shared proofs`() = runTest { assertScale(4096) }

    @Test fun `256 sibling branches preserve paths receipts and shared proofs`() = runTest { assertBreadth(256) }

    @Test fun `4096 sibling branches preserve paths receipts and shared proofs`() = runTest { assertBreadth(4096) }

    private suspend fun assertBreadth(branchCount: Int) {
        val f = QueryImpactExecutionFixture(ownerEnd = 1000 + branchCount * 8)
        val arguments =
            List(branchCount) { index ->
                val start = 1003 + index * 8
                f.argument(f.call("consume", start, start + 4))
            }
        val expectations =
            listOf(
                ImpactReadExpectation(
                    f.producer.site,
                    arguments.map { f.edge(f.producer.site, it, ValueTransferKind.ARGUMENT) },
                )
            ) + arguments.map { ImpactReadExpectation(it, causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL)) }
        val native = f.script(expectations)
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                f.service(native.port).run(runnerRequest(f, f.plan, branchCount)),
            )
        assertInstanceOf(QueryContinuationState.Terminal::class.java, result.continuation)
        val rows = assertInstanceOf(QueryRows.ValuePaths::class.java, result.result.rows)
        val ledger = assertInstanceOf(QueryValuePathAccounting.Investigated::class.java, rows.accounting).ledger
        assertEquals(expectations.map { it.site }, native.examined)
        native.assertConsumed()
        assertEquals(1L + branchCount, observedWork(result))
        assertEquals(listOf(f.producer.site), ledger.seeds)
        assertEquals(arguments, rows.values.map { it.destination })
        assertEquals(rows.values, ledger.paths)
        assertEquals(expectations.map { it.site }, ledger.observations.map { it.source })
        val canonical = invocation(ledger.observations.first().transfers.first().target)
        assertScalePaths(rows, List(branchCount) { f.producer }, arguments, canonical)
        ledger.observations.forEachIndexed { index, read ->
            assertEquals(native.grants[index], read.domain.budget)
            assertEquals(f.lease.identity, read.source.basis)
            assertEquals(RelationSearchBoundary.WORKSPACE_EXPANSION, read.domain.boundary)
            assertEquals(1L, read.examinedWorkUnits.value)
            assertEquals(read.receipts, ledger.readReceipts.getValue(read.source))
            if (index > 0) assertShared(canonical, invocation(read.source))
        }
        assertUnresolved(ledger)
        assertTrue(result.result.failures.isEmpty())
        assertTrue(ledger.readRejections.isEmpty())
    }

    private suspend fun assertScale(producerCount: Int) {
        val f = QueryImpactExecutionFixture(ownerEnd = 1000 + producerCount * 8)
        val producers =
            List(producerCount) { index ->
                val start = 1000 + index * 8
                val call = f.call("produce", start, start + 2)
                QueryImpactProducer.admit(call.resultSite(), call).refined()
            }
        val arguments =
            List(producerCount) { index ->
                val start = 1003 + index * 8
                f.argument(f.call("consume", start, start + 4))
            }
        assertEquals(invocation(arguments.first()).callable, invocation(arguments.last()).callable)
        assertNotSame(invocation(arguments.first()).callable, invocation(arguments.last()).callable)
        assertNotSame(invocation(arguments.first()).signature, invocation(arguments.last()).signature)
        val expectations =
            producers.zip(arguments).flatMap { (producer, argument) ->
                listOf(
                    ImpactReadExpectation(
                        producer.site,
                        listOf(f.edge(producer.site, argument, ValueTransferKind.ARGUMENT)),
                    ),
                    ImpactReadExpectation(argument, causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL)),
                )
            }
        val native = f.script(expectations)
        val request = runnerRequest(f, f.plan(producers = producers), producerCount)
        val result = assertInstanceOf(QueryExecutionResult.Qualified::class.java, f.service(native.port).run(request))
        assertScaleResult(result, native, producers, arguments, request)
    }

    private fun assertScaleResult(
        result: QueryExecutionResult.Qualified,
        native: QueryImpactExecutionFixture.Script,
        producers: List<QueryImpactProducer>,
        arguments: List<ValueSite>,
        request: QueryExecutionRequest,
    ) {
        assertInstanceOf(
            QueryContinuationState.Terminal::class.java,
            result.continuation,
            "rows=${(result.result.rows as QueryRows.ValuePaths).values.size}, work=${observedWork(result)}, " +
                "limitations=${result.coverage.limitations}",
        )
        val rows = assertInstanceOf(QueryRows.ValuePaths::class.java, result.result.rows)
        val ledger = assertInstanceOf(QueryValuePathAccounting.Investigated::class.java, rows.accounting).ledger
        val expectedSources =
            producers.zip(arguments).flatMap { (producer, argument) -> listOf(producer.site, argument) }
        assertEquals(expectedSources, native.examined)
        native.assertConsumed()
        assertEquals(expectedSources, ledger.observations.map { it.source })
        assertEquals(expectedSources, ledger.readReceipts.keys.toList())
        assertEquals(2L * producers.size, observedWork(result))
        assertEquals(producers.map { it.site }, ledger.seeds)
        assertEquals(producers.map { it.site }, rows.values.map { it.producer })
        assertEquals(arguments, rows.values.map { it.destination })
        assertEquals(rows.values, ledger.paths)
        assertTrue(result.result.failures.isEmpty())
        assertTrue(ledger.readRejections.isEmpty())
        assertUnresolved(ledger)
        val canonical = invocation(ledger.observations.first().transfers.single().target)
        assertScaleObservations(ledger, native, request, arguments, canonical)
        assertScalePaths(rows, producers, arguments, canonical)
        native.grants.forEach { grant ->
            assertSame(ResourceBudget.RunnerControlled, grant.resources)
            assertEquals(producers.size, grant.outputResults.value)
            assertEquals(request.budget.returnedBytes.value, grant.returnedBytes.value)
        }
    }

    private fun assertScaleObservations(
        ledger: QueryImpactLedger,
        native: QueryImpactExecutionFixture.Script,
        request: QueryExecutionRequest,
        arguments: List<ValueSite>,
        canonical: ValueInvocation,
    ) {
        ledger.observations.forEachIndexed { index, observation ->
            assertEquals(request.lease.identity, observation.source.basis)
            assertEquals(RelationSearchBoundary.WORKSPACE_EXPANSION, observation.domain.boundary)
            assertEquals(native.grants[index], observation.domain.budget)
            assertEquals(1L, observation.examinedWorkUnits.value)
            assertEquals(observation.receipts, ledger.readReceipts.getValue(observation.source))
            assertEquals(1L, observation.receipts.single().examinedWorkUnits.value)
            if (index % 2 == 0) {
                assertEquals(arguments[index / 2], observation.transfers.single().target)
                assertShared(canonical, invocation(observation.transfers.single().target))
            } else {
                assertTrue(observation.transfers.isEmpty())
                assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, observation.obligations.single().cause)
                assertShared(canonical, invocation(observation.source))
            }
        }
    }

    private fun assertScalePaths(
        rows: QueryRows.ValuePaths,
        producers: List<QueryImpactProducer>,
        arguments: List<ValueSite>,
        canonical: ValueInvocation,
    ) {
        rows.values.forEachIndexed { index, path ->
            val step = assertInstanceOf(QueryImpactStep.Compiler::class.java, path.steps.single())
            assertEquals(producers[index].site, step.source)
            assertEquals(arguments[index], step.target)
            assertEquals(ValueTransferKind.ARGUMENT, step.transfer.kind)
            assertSame(QueryImpactRepresentation.NotModeled, path.representation)
            assertFlowTerminal(path.terminal, arguments[index])
            assertShared(canonical, invocation(path.destination))
        }
    }

    private fun runnerRequest(
        fixture: QueryImpactExecutionFixture,
        plan: AdmittedQueryPlan,
        results: Int,
        checkpoint: QueryCheckpoint? = null,
    ): QueryExecutionRequest {
        val bounded = fixture.request(plan = plan, results = results, returnedBytes = 2_000_000_000L)
        return QueryExecutionRequest.create(
                plan,
                fixture.lease,
                bounded.budget.copy(resources = ResourceBudget.RunnerControlled),
                checkpoint,
            )
            .refined()
    }

    private fun observedWork(result: QueryExecutionResult): Long =
        assertInstanceOf(QueryWorkUsage.Observed::class.java, result.workUsage).count.value

    private fun invocation(site: ValueSite): ValueInvocation =
        assertInstanceOf(ValueRole.Argument::class.java, site.role).call

    private fun assertShared(expected: ValueInvocation, actual: ValueInvocation) {
        assertSame(expected.callable, actual.callable)
        assertSame(expected.signature, actual.signature)
        assertSame(expected.executionOwner, actual.executionOwner)
    }

    private fun assertFlowTerminal(terminal: QueryImpactTerminal, site: ValueSite) {
        val flow = assertInstanceOf(QueryImpactTerminal.Unresolved.Flow::class.java, terminal)
        assertEquals(site, flow.obligation.site)
        assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, flow.obligation.cause)
    }

    private fun assertUnresolved(ledger: QueryImpactLedger) {
        val closure = assertInstanceOf(QueryImpactClosure.Unresolved::class.java, ledger.closure)
        assertEquals(setOf(QueryImpactRequiredObligation.NATIVE_FLOW), closure.required)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture refinement rejected: $failure")
        }
}
