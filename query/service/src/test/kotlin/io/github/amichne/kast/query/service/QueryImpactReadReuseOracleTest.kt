package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionRequestFailure
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

/** These cases characterize exact-site read reuse. They do not prove acyclic callee-summary reuse or invalidation. */
class QueryImpactReadReuseOracleTest {
    @Test
    fun `shared native reads keep both prefixes and every model or opaque qualification`() = runTest {
        val case = AutomaticValueQueryCase()
        val fixture = case.fixture
        val middle = fixture.site(ALTERNATE_START, ValueRole.ExpressionResult)
        val alternative = fixture.edge(fixture.producer.site, middle, ValueTransferKind.BRANCH_ALTERNATIVE)
        val arrival = fixture.edge(middle, case.receiver, ValueTransferKind.RECEIVER)
        val native =
            fixture.script(
                listOf(
                    ImpactReadExpectation(fixture.producer.site, edges = listOf(case.intoReceiver, alternative)),
                    ImpactReadExpectation(
                        site = case.receiver,
                        causes = listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL),
                        applications = listOf(case.application),
                    ),
                    ImpactReadExpectation(case.result, edges = listOf(case.intoSink)),
                    ImpactReadExpectation(case.consumption, causes = listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL)),
                    ImpactReadExpectation(middle, edges = listOf(arrival)),
                )
            )
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                fixture.service(native.port).run(fixture.request()),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        val direct = listOf(QueryImpactStep.Compiler(case.intoReceiver))
        val indirect = listOf(QueryImpactStep.Compiler(alternative), QueryImpactStep.Compiler(arrival))
        val suffix = listOf(QueryImpactStep.ModeledValue(case.application), QueryImpactStep.Compiler(case.intoSink))
        assertEquals(listOf(direct, direct + suffix, indirect, indirect + suffix), rows.values.map { it.steps })
        assertEquals(
            listOf(case.receiver, case.consumption, case.receiver, case.consumption),
            rows.values.map { it.destination },
        )
        assertEquals(
            List(QUALIFIED_BRANCHES) { ValueFlowUnsupportedCause.EXTERNAL_CALL },
            rows.values.map { (it.terminal as QueryImpactTerminal.Unresolved.Flow).obligation.cause },
        )
        assertEquals(
            listOf(fixture.producer.site, case.receiver, case.result, case.consumption, middle),
            native.examined,
        )
        val ledger = (rows.accounting as QueryValuePathAccounting.Investigated).ledger
        assertEquals(SHARED_NATIVE_READS, ledger.observations.size)
        assertEquals(listOf(case.application), ledger.observations.single { it.source == case.receiver }.applications)
        native.assertConsumed()
    }

    @Test
    fun `a fresh investigation does not borrow the previous query local read cache`() = runTest {
        val fixture = QueryImpactExecutionFixture()
        val native =
            fixture.script(
                List(2) {
                    ImpactReadExpectation(
                        fixture.producer.site,
                        causes = listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL),
                    )
                }
            )
        val service = fixture.service(native.port)
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(fixture.request()))
        val second = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(fixture.request()))
        val firstRows = first.result.rows as QueryRows.ValuePaths
        val secondRows = second.result.rows as QueryRows.ValuePaths
        assertEquals(firstRows.values, secondRows.values)
        assertEquals(listOf(fixture.producer.site, fixture.producer.site), native.examined)
        assertEquals(native.grants[0], native.grants[1])
        val firstLedger = (firstRows.accounting as QueryValuePathAccounting.Investigated).ledger
        val secondLedger = (secondRows.accounting as QueryValuePathAccounting.Investigated).ledger
        assertNotSame(firstLedger, secondLedger)
        assertEquals(1, firstLedger.observations.size)
        assertEquals(1, secondLedger.observations.size)
        native.assertConsumed()
    }

    @Test
    fun `a checkpoint cannot cross the authority model set or source domain of its original question`() = runTest {
        val case = AutomaticValueQueryCase()
        val fixture = case.fixture
        val native =
            fixture.script(
                listOf(ImpactReadExpectation(fixture.producer.site, edges = listOf(case.intoReceiver), work = 1))
            )
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                fixture.service(native.port).run(fixture.request(work = 1)),
            )
        val checkpoint = (result.continuation as QueryContinuationState.Resumable).checkpoint
        val budget = fixture.request(work = 1).budget
        val changedAuthority = QueryImpactExecutionFixture(generation = 2)
        assertEquals(
            Refinement.Rejected(QueryExecutionRequestFailure.CHECKPOINT_MISMATCH),
            QueryExecutionRequest.create(
                plan = fixture.plan,
                lease = changedAuthority.lease,
                budget = budget,
                checkpoint = checkpoint,
            ),
        )
        assertEquals(
            Refinement.Rejected(QueryExecutionRequestFailure.CHECKPOINT_MISMATCH),
            QueryExecutionRequest.create(
                plan = fixture.plan(models = listOf(case.originRule)),
                lease = fixture.lease,
                budget = budget,
                checkpoint = checkpoint,
            ),
        )
        assertEquals(
            Refinement.Rejected(QueryExecutionRequestFailure.CHECKPOINT_MISMATCH),
            QueryExecutionRequest.create(
                plan = fixture.plan(domain = RelationSearchBoundary.RETAINED_SUBJECT),
                lease = fixture.lease,
                budget = budget,
                checkpoint = checkpoint,
            ),
        )
        assertEquals(listOf(fixture.producer.site), native.examined)
        native.assertConsumed()
    }

    private companion object {
        const val QUALIFIED_BRANCHES = 4
        const val SHARED_NATIVE_READS = 5
        const val ALTERNATE_START = 50
    }
}
