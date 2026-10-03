package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionRequestFailure
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactExecutionStop
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.accountingStatus
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scripted native observations exercise the production interpreter; they establish no installed compiler behavior. */
class QueryImpactExecutionTest {
    @Test
    fun `all exact branches survive shared destination and native destination reads once`() = runTest {
        val f = QueryImpactExecutionFixture()
        val a = f.producer.site
        val b = f.site(30, ValueRole.LocalBinding)
        val c = f.site(40, ValueRole.LocalBinding)
        val d = f.site(50, ValueRole.LocalRead)
        val ab = f.edge(a, b, ValueTransferKind.LOCAL_BINDING)
        val ac = f.edge(a, c, ValueTransferKind.LOCAL_BINDING)
        val bd = f.edge(b, d, ValueTransferKind.LOCAL_READ)
        val cd = f.edge(c, d, ValueTransferKind.LOCAL_READ)
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(a, listOf(ab, ac)),
                    ImpactReadExpectation(b, listOf(bd)),
                    ImpactReadExpectation(d),
                    ImpactReadExpectation(c, listOf(cd)),
                )
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Complete::class.java, f.service(native.port).run(f.request()))
        val rows = assertInstanceOf(QueryRows.ValuePaths::class.java, result.result.rows)
        assertEquals(
            listOf(listOf(ab, bd), listOf(ac, cd)),
            rows.values.map { it.steps.map { step -> (step as QueryImpactStep.Compiler).transfer } },
        )
        assertEquals(listOf(a, b, d, c), native.examined)
        assertEquals(QueryValuePathAccountingStatus.Conserved, rows.accountingStatus)
        assertEquals(4, (rows.accounting as QueryValuePathAccounting.Investigated).ledger.observations.size)
        native.assertConsumed()
    }

    @Test
    fun `output paging preserves full accounting and does not replay compiler work`() = runTest {
        val f = QueryImpactExecutionFixture()
        val a = f.producer.site
        val b = f.site(30, ValueRole.LocalBinding)
        val c = f.site(40, ValueRole.LocalBinding)
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(
                        a,
                        listOf(
                            f.edge(a, b, ValueTransferKind.LOCAL_BINDING),
                            f.edge(a, c, ValueTransferKind.LOCAL_BINDING),
                        ),
                    ),
                    ImpactReadExpectation(b),
                    ImpactReadExpectation(c),
                )
            )
        val service = f.service(native.port)
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(f.request(results = 1)))
        assertEquals(3, native.examined.size)
        assertTrue(native.grants.first().resources.resultLimit.value > 1)
        assertTrue(
            (first.result.rows as QueryRows.ValuePaths).accountingStatus
                is QueryValuePathAccountingStatus.SelectedSubset
        )
        val original =
            ((first.result.rows as QueryRows.ValuePaths).accounting as QueryValuePathAccounting.Investigated).ledger
        val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint
        val last =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                service.run(f.request(results = 1, checkpoint = checkpoint)),
            )
        val rows = last.result.rows as QueryRows.ValuePaths
        assertSame(original, (rows.accounting as QueryValuePathAccounting.Investigated).ledger)
        assertEquals(
            listOf(b, c),
            (first.result.rows as QueryRows.ValuePaths).values.map { it.destination } +
                rows.values.map { it.destination },
        )
        assertTrue(last.coverage.limitations.contains(QueryLimitation.ROW_SELECTION_INCOMPLETE))
        assertEquals(3, native.examined.size)
        assertTrue(last.continuation is QueryContinuationState.Terminal)
        native.assertConsumed()
    }

    @Test
    fun `work cutoff retains native cache and final resumed closure clears temporary uncertainty`() = runTest {
        val f = QueryImpactExecutionFixture()
        val a = f.producer.site
        val b = f.site(30, ValueRole.LocalBinding)
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(a, listOf(f.edge(a, b, ValueTransferKind.LOCAL_BINDING)), work = 3),
                    ImpactReadExpectation(b),
                )
            )
        val service = f.service(native.port)
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(f.request(work = 3)))
        assertEquals(listOf(a), native.examined)
        assertEquals(emptyList<QueryImpactPath>(), (first.result.rows as QueryRows.ValuePaths).values)
        assertTrue(QueryLimitation.WORK_LIMIT_REACHED in first.coverage.limitations)
        val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint
        val last =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service.run(f.request(work = 3, checkpoint = checkpoint)),
            )
        assertEquals(b, (last.result.rows as QueryRows.ValuePaths).values.single().destination)
        assertEquals(listOf(a, b), native.examined)
        assertEquals(listOf(3L, 3L), native.grants.map { it.resources.workUnitLimit.value })
        native.assertConsumed()
    }

    @Test
    fun `unsupported obligations remain separate terminals without guessed model semantics`() = runTest {
        val f = QueryImpactExecutionFixture()
        val a = f.producer.site
        val causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL, ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW)
        val native = f.script(listOf(ImpactReadExpectation(a, causes = causes)))
        val result =
            assertInstanceOf(QueryExecutionResult.Qualified::class.java, f.service(native.port).run(f.request()))
        val rows = result.result.rows as QueryRows.ValuePaths
        assertEquals(
            causes,
            rows.values.map { ((it.terminal as QueryImpactTerminal.Unresolved.Flow).obligation.cause) },
        )
        assertTrue(rows.values.all { it.representation == QueryImpactRepresentation.NotModeled })
        assertEquals(
            setOf(QueryImpactRequiredObligation.NATIVE_FLOW),
            (rows.accountingStatus as QueryValuePathAccountingStatus.Unresolved).required,
        )
        native.assertConsumed()
    }

    @Test
    fun `rejected read preserves exact receipt and finite failure without invented observation`() = runTest {
        val f = QueryImpactExecutionFixture()
        var count = 0
        val service =
            f.service(
                ValueFlowCompilerPort {
                    count++
                    ValueFlowRead.Rejected(ValueFlowRejection.AUTHORITY_MOVED, RelationWorkCount.parse(3).value())
                }
            )
        val result = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(f.request(work = 3)))
        val rows = result.result.rows as QueryRows.ValuePaths
        val ledger = (rows.accounting as QueryValuePathAccounting.Investigated).ledger
        assertEquals(emptyList<ValueFlowStep>(), ledger.observations)
        val rejection = ledger.readRejections.single() as QueryImpactReadRejection.Native
        assertEquals(ValueFlowRejection.AUTHORITY_MOVED, rejection.cause)
        assertEquals(3L, rejection.examinedWorkUnits.value)
        assertEquals(
            rejection,
            (rows.values.single().terminal as QueryImpactTerminal.Unresolved.ReadRejected).rejection,
        )
        assertEquals(1, count)
    }

    @Test
    fun `cycle remains an exact unresolved route and never starts a repeated native read`() = runTest {
        val f = QueryImpactExecutionFixture()
        val a = f.producer.site
        val b = f.site(30, ValueRole.LocalBinding)
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(a, listOf(f.edge(a, b, ValueTransferKind.LOCAL_BINDING))),
                    ImpactReadExpectation(b, listOf(f.edge(b, a, ValueTransferKind.BRANCH_ALTERNATIVE))),
                )
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Qualified::class.java, f.service(native.port).run(f.request()))
        val path = (result.result.rows as QueryRows.ValuePaths).values.single()
        assertEquals(2, path.steps.size)
        assertTrue(path.terminal is QueryImpactTerminal.Unresolved.ExecutionStop)
        assertEquals(listOf(a, b), native.examined)
        native.assertConsumed()
    }

    @Test
    fun `foreign authority and changed admitted question cannot resume retained native state`() = runTest {
        val f = QueryImpactExecutionFixture()
        val b = f.site(30, ValueRole.LocalBinding)
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(
                        f.producer.site,
                        listOf(f.edge(f.producer.site, b, ValueTransferKind.LOCAL_BINDING)),
                    )
                )
            )
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                f.service(native.port).run(f.request(work = 1)),
            )
        val checkpoint = (result.continuation as QueryContinuationState.Resumable).checkpoint
        val other = QueryImpactExecutionFixture(generation = 2)
        assertEquals(
            Refinement.Rejected(QueryExecutionRequestFailure.CHECKPOINT_MISMATCH),
            QueryExecutionRequest.create(f.plan, other.lease, f.request().budget, checkpoint),
        )
        assertEquals(
            Refinement.Rejected(QueryExecutionRequestFailure.CHECKPOINT_MISMATCH),
            QueryExecutionRequest.create(other.plan, other.lease, f.request().budget, checkpoint),
        )
        native.assertConsumed()
    }

    @Test
    fun `exact domain exclusion is accounted before any native enumeration`() = runTest {
        val f = QueryImpactExecutionFixture()
        val exact =
            SymbolSearchScope.ExactFile(
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).value(),
                        Path.of("/fixture/Other.kt"),
                    )
                    .value(),
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
            )
        val plan = f.plan(domain = RelationSearchBoundary.Explicit(exact))
        val native = f.script(emptyList())
        val complete =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                f.service(native.port).run(f.request(plan = plan)),
            )
        val rows = complete.result.rows as QueryRows.ValuePaths
        assertTrue(rows.values.single().terminal is QueryImpactTerminal.ExplicitScopeExclusion)
        assertEquals(emptyList<ValueSite>(), native.examined)
        assertEquals(QueryValuePathAccountingStatus.Conserved, rows.accountingStatus)
        native.assertConsumed()
    }

    @Test
    fun `checkpoint grant too small records capacity evidence before native call`() = runTest {
        val f = QueryImpactExecutionFixture()
        val native = f.script(emptyList())
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                f.service(native.port).run(f.request(checkpointBytes = 1)),
            )
        val path = (result.result.rows as QueryRows.ValuePaths).values.single()
        val stop =
            (path.terminal as QueryImpactTerminal.Unresolved.ExecutionStop).stop
                as QueryImpactExecutionStop.CheckpointCapacity
        assertTrue(stop.required.value > stop.available.value)
        assertEquals(1L, stop.available.value)
        assertEquals(emptyList<ValueSite>(), native.examined)
        native.assertConsumed()
    }

    @Test
    fun `native step for different exact occurrence remains contract rejection with receipt`() = runTest {
        val f = QueryImpactExecutionFixture()
        val foreign = f.site(30, ValueRole.LocalRead)
        val native = f.script(listOf(ImpactReadExpectation(foreign, work = 2)))
        val port = ValueFlowCompilerPort { input -> native.port.read(input.copy(source = foreign)) }
        val result = assertInstanceOf(QueryExecutionResult.Qualified::class.java, f.service(port).run(f.request()))
        val rows = result.result.rows as QueryRows.ValuePaths
        val rejection =
            ((rows.values.single().terminal as QueryImpactTerminal.Unresolved.ReadRejected).rejection
                as QueryImpactReadRejection.Contract)
        assertEquals(f.producer.site, rejection.source)
        assertEquals(ValueFlowStepFailure.SOURCE_MISMATCH, rejection.cause)
        assertEquals(2L, rejection.examinedWorkUnits.value)
        native.assertConsumed()
    }
}

private fun <V> Refinement<V, *>.value(): V = (this as Refinement.Refined).value
