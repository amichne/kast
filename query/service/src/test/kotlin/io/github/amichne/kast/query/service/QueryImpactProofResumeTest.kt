package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactClosure
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.relation.contract.LocalBindingReferenceScan
import io.github.amichne.kast.relation.contract.LocalReferenceKey
import io.github.amichne.kast.relation.contract.LocalReferenceKind
import io.github.amichne.kast.relation.contract.LocalReferenceResolution
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowSuspensionCause
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactProofResumeTest {
    @Test
    fun `native suspension restores canonical proofs without repeated candidate confirmation`() = runTest {
        val case = NativeResumeCase()
        assertNotSame(invocation(case.initialArgument).callable, invocation(case.arguments.first()).callable)
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, case.service.run(case.request(1)))
        val checkpoint =
            assertInstanceOf(
                PipelineCheckpoint::class.java,
                assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation).checkpoint,
            )
        val saved = requireNotNull(checkpoint.impact)
        val savedSource = assertInstanceOf(ValueFlowRead.Observed::class.java, saved.reads[case.f.producer.site]).step
        val savedBinding = assertInstanceOf(ValueFlowRead.Observed::class.java, saved.reads[case.binding]).step
        val canonical = invocation(savedSource.transfers.single().target)
        assertSuspended(case, first, saved, savedBinding)

        val resumed =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                case.service.run(case.request(2, checkpoint)),
            )
        assertResumed(case, resumed, canonical)
        assertSame(
            savedSource,
            assertInstanceOf(ValueFlowRead.Observed::class.java, saved.reads[case.f.producer.site]).step,
        )
        assertEquals(listOf(case.locals.first()), savedBinding.transfers.map { it.target })
        assertEquals(setOf(case.binding), saved.remainders.keys)
    }

    private fun assertSuspended(
        case: NativeResumeCase,
        first: QueryExecutionResult.Qualified,
        saved: QueryImpactSnapshot,
        savedBinding: ValueFlowStep,
    ) {
        val suspended = assertInstanceOf(ValueFlowRead.Suspended::class.java, case.bindingReads.single())
        assertEquals(ValueFlowSuspensionCause.RESULT_LIMIT_REACHED, suspended.cause)
        assertEquals(listOf(0), case.confirmations)
        assertEquals(listOf(case.locals.first()), savedBinding.transfers.map { it.target })
        assertEquals(setOf(case.binding), saved.remainders.keys)
        assertEquals(5L, observedWork(first))
        assertTrue((first.result.rows as QueryRows.ValuePaths).values.isEmpty())
    }

    private fun assertResumed(
        case: NativeResumeCase,
        resumed: QueryExecutionResult.Qualified,
        canonical: ValueInvocation,
    ) {
        assertInstanceOf(QueryContinuationState.Terminal::class.java, resumed.continuation)
        val rows = assertInstanceOf(QueryRows.ValuePaths::class.java, resumed.result.rows)
        val ledger = assertInstanceOf(QueryValuePathAccounting.Investigated::class.java, rows.accounting).ledger
        assertEquals(listOf(0, 1), case.confirmations)
        assertEquals(2, case.bindingReads.size)
        assertInstanceOf(ValueFlowRead.Observed::class.java, case.bindingReads.last())
        assertEquals(7L, observedWork(resumed))
        assertEquals(case.arguments, rows.values.map { it.destination })
        assertPaths(case, rows, canonical)
        assertReads(case, ledger, canonical)
        val closure = assertInstanceOf(QueryImpactClosure.Unresolved::class.java, ledger.closure)
        assertEquals(setOf(QueryImpactRequiredObligation.NATIVE_FLOW), closure.required)
        assertEquals(case.expectations.map { it.site }, case.nonlocal.examined)
        case.nonlocal.assertConsumed()
    }

    private fun assertPaths(case: NativeResumeCase, rows: QueryRows.ValuePaths, canonical: ValueInvocation) {
        rows.values.forEachIndexed { index, path ->
            assertEquals(case.f.producer.site, path.producer)
            assertEquals(
                listOf(case.initialArgument, case.binding, case.locals[index], case.arguments[index]),
                path.steps.map { it.target },
            )
            assertEquals(
                listOf(
                    ValueTransferKind.ARGUMENT,
                    ValueTransferKind.LOCAL_BINDING,
                    ValueTransferKind.LOCAL_READ,
                    ValueTransferKind.ARGUMENT,
                ),
                path.steps.map { assertInstanceOf(QueryImpactStep.Compiler::class.java, it).transfer.kind },
            )
            val flow = assertInstanceOf(QueryImpactTerminal.Unresolved.Flow::class.java, path.terminal)
            assertEquals(case.arguments[index], flow.obligation.site)
            assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, flow.obligation.cause)
            assertShared(canonical, invocation(path.destination))
        }
    }

    private fun assertReads(case: NativeResumeCase, ledger: QueryImpactLedger, canonical: ValueInvocation) {
        val observations = ledger.observations.associateBy { it.source }
        assertEquals((case.expectations.map { it.site } + case.binding).toSet(), observations.keys)
        case.arguments.forEach { argument ->
            assertShared(canonical, invocation(observations.getValue(argument).source))
        }
        ledger.observations.forEach { observation ->
            assertEquals(case.f.lease.identity, observation.source.basis)
            assertEquals(RelationSearchBoundary.WORKSPACE_EXPANSION, observation.domain.boundary)
        }
        val bindingObservation = observations.getValue(case.binding)
        assertEquals(case.locals, bindingObservation.transfers.map { it.target })
        assertEquals(listOf(3L, 3L), bindingObservation.receipts.map { it.examinedWorkUnits.value })
        assertEquals(case.bindingGrants, bindingObservation.receipts.map { it.domain.budget })
        assertEquals(listOf(1, 2), case.bindingGrants.map { it.outputResults.value })
        assertEquals(12L, ledger.readReceipts.values.flatten().sumOf { it.examinedWorkUnits.value })
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

    private class NativeResumeCase {
        val f = QueryImpactExecutionFixture()
        val initialArgument = f.argument(f.call("consume", 30, 40))
        val binding = f.site(50, ValueRole.LocalBinding)
        val locals = listOf(f.site(60, ValueRole.LocalRead), f.site(70, ValueRole.LocalRead))
        val arguments = listOf(f.argument(f.call("consume", 100, 110)), f.argument(f.call("consume", 120, 130)))
        val expectations =
            listOf(
                ImpactReadExpectation(
                    f.producer.site,
                    listOf(f.edge(f.producer.site, initialArgument, ValueTransferKind.ARGUMENT)),
                ),
                ImpactReadExpectation(
                    initialArgument,
                    listOf(f.edge(initialArgument, binding, ValueTransferKind.LOCAL_BINDING)),
                ),
            ) +
                locals.zip(arguments).flatMap { (local, argument) ->
                    listOf(
                        ImpactReadExpectation(local, listOf(f.edge(local, argument, ValueTransferKind.ARGUMENT))),
                        ImpactReadExpectation(argument, causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL)),
                    )
                }
        val nonlocal = f.script(expectations)
        val confirmations = mutableListOf<Int>()
        val bindingReads = mutableListOf<ValueFlowRead>()
        val bindingGrants = mutableListOf<RelationBudget>()
        val service =
            f.service(
                ValueFlowCompilerPort { request ->
                    if (request.source != binding) nonlocal.port.read(request) else readBinding(request)
                }
            )

        fun request(results: Int, checkpoint: QueryCheckpoint? = null): QueryExecutionRequest {
            val bounded = f.request(plan = f.plan, results = results, returnedBytes = 2_000_000_000L)
            return QueryExecutionRequest.create(
                    f.plan,
                    f.lease,
                    bounded.budget.copy(resources = ResourceBudget.RunnerControlled),
                    checkpoint,
                )
                .refined()
        }

        private fun readBinding(request: ValueFlowRequest): ValueFlowRead {
            val domain = RelationRequest.start(f.owner, RelationMeaning.References, request.budget, request.boundary)
            return LocalBindingReferenceScan(
                    request,
                    domain,
                    ::enumerate,
                    ::key,
                    { index ->
                        confirmations += index
                        LocalReferenceResolution.Transfer(locals[index])
                    },
                    {},
                    { 0L },
                )
                .read()
                .also {
                    bindingReads += it
                    bindingGrants += request.budget
                }
        }

        private fun enumerate(visit: (Int) -> Boolean) {
            for (index in locals.indices) if (!visit(index)) break
        }

        private fun key(index: Int): LocalReferenceKey {
            val range = ExactDeclarationTextRange.parse(150 + index, 151 + index).refined()
            return LocalReferenceKey(
                range,
                range,
                LocalReferenceKind.fromBoundary("fixture.Reference").refined(),
            )
        }
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture refinement rejected: $failure")
    }
