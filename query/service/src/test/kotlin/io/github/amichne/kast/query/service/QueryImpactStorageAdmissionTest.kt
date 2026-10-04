package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactStorageAdmissionTest {
    @Test
    fun `storage distinguishes shared proof objects from physically distinct equal declarations`() {
        val first = QueryImpactExecutionFixture()
        val other = QueryImpactExecutionFixture()
        val shared = path(first)
        val detachedCopy = path(first, other.site(30, ValueRole.LocalBinding))
        assertEquals(shared.steps, detachedCopy.steps)
        assertSame(shared.steps.first().target, shared.steps.last().source)
        assertTrue(shared.retainedBytes < detachedCopy.retainedBytes)
        assertTrue(shared.retainedBytes < 524288L, "Three sites fit the existing bounded checkpoint grant")
    }

    @Test
    fun `repeated root references are charged without removing an evidence row`() {
        val f = QueryImpactExecutionFixture()
        val value = path(f)
        val graph = QueryImpactRetainedGraph()
        val first = graph.path(value)
        val again = graph.path(value)
        assertTrue(first > again)
        assertEquals(8L * 8L, again, "One stored pointer times the unchanged conservative factor")
        assertEquals(2, listOf(value, value).size)
    }

    @Test
    fun `shared modeled branch prefixes finish within existing checkpoint capacity with every path intact`() = runTest {
        val f = QueryImpactExecutionFixture()
        val a = f.producer.site
        val b = f.site(30, ValueRole.LocalBinding)
        val c = f.site(40, ValueRole.LocalBinding)
        val d = f.site(50, ValueRole.LocalRead)
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
                    ImpactReadExpectation(b, listOf(f.edge(b, d, ValueTransferKind.LOCAL_READ))),
                    ImpactReadExpectation(d),
                    ImpactReadExpectation(c, listOf(f.edge(c, d, ValueTransferKind.LOCAL_READ))),
                )
            )
        val result =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                f.service(native.port)
                    .run(
                        f.request(
                            plan = f.plan(listOf(origin(f))),
                            checkpointBytes = QueryByteLimit.DefaultCheckpoint.value,
                        )
                    ),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        assertEquals(2, rows.values.size)
        assertEquals(4, (rows.accounting as QueryValuePathAccounting.Investigated).ledger.observations.size)
        for (value in rows.values) {
            val evidence = (value.representation as QueryImpactRepresentation.Present).evidence
            val history = evidence.branches.single().history.filterIsInstance<RepresentationHistory.CompilerTransfer>()
            assertEquals(2, history.size)
            value.steps.zip(history).forEach { (step, preserved) ->
                assertSame((step as QueryImpactStep.Compiler).transfer, preserved.transfer)
            }
        }
        native.assertConsumed()
    }

    private fun origin(f: QueryImpactExecutionFixture): RepresentationRule.Origin {
        fun id(value: String) = (ModelIdentifier.parse(value) as Refinement.Refined).value
        val model =
            ContractModelIdentity(
                id("representation"),
                (ModelVersion.parse(1) as Refinement.Refined).value,
                id("review:913"),
            )
        val domain = (RepresentationDomain.admit(model, listOf(id("ENCRYPTED"))) as Refinement.Refined).value
        val state = (domain.state(id("ENCRYPTED")) as Refinement.Refined).value
        return (RepresentationRule.Origin.admit(
                ModelRuleReference(model, id("origin")),
                f.bind(f.producer.invocation.callable, ModelValuePosition.Result),
                state,
            ) as Refinement.Refined)
            .value
    }

    @Test
    fun `standalone output keeps conservative byte protection`() = runTest {
        val f = QueryImpactExecutionFixture()
        val native = f.script(listOf(ImpactReadExpectation(f.producer.site)))
        val execution = f.service(native.port).run(smallRequest(f))
        val result = assertInstanceOf(QueryExecutionResult.Qualified::class.java, execution)
        assertEquals(emptyList<QueryImpactPath>(), (result.result.rows as QueryRows.ValuePaths).values)
        assertEquals(QueryContinuationState.Terminal(QueryTerminalReason.OUTPUT_ITEM_TOO_LARGE), result.continuation)
        native.assertConsumed()
    }

    @Test
    fun `paired presentation leaves storage charge for checkpoint ownership`() = runTest {
        val f = QueryImpactExecutionFixture()
        val native = f.script(listOf(ImpactReadExpectation(f.producer.site)))
        var fitted = false
        QueryPresentationExecution.evaluateAndFit(
            evaluate = { owner ->
                assertTrue(owner.admitValuePath() is Refinement.Refined)
                f.service(native.port, owner).run(smallRequest(f))
            },
            fit = { execution ->
                fitted = true
                val result = assertInstanceOf(QueryExecutionResult.Complete::class.java, execution)
                assertEquals(1, (result.result.rows as QueryRows.ValuePaths).values.size)
            },
        )
        assertTrue(fitted)
        native.assertConsumed()
    }

    @Test
    fun `expired presentation permission rejects before semantic effects`() = runTest {
        val f = QueryImpactExecutionFixture()
        val native = f.script(emptyList())
        lateinit var leaked: QueryService
        QueryPresentationExecution.evaluateAndFit(
            evaluate = { owner -> leaked = f.service(native.port, owner) },
            fit = { Unit },
        )
        assertEquals(
            QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            leaked.run(f.request()),
        )
        assertEquals(emptyList<ValueSite>(), native.examined)
        native.assertConsumed()
    }

    private fun smallRequest(f: QueryImpactExecutionFixture): QueryExecutionRequest {
        val request = f.request()
        return (QueryExecutionRequest.create(
                request.plan,
                request.lease,
                request.budget.copy(returnedBytes = (QueryByteLimit.parse(10000) as Refinement.Refined).value),
            ) as Refinement.Refined)
            .value
    }

    private fun path(f: QueryImpactExecutionFixture, copy: ValueSite? = null): QueryImpactPath {
        val producer = f.producer.site
        val binding = f.site(30, ValueRole.LocalBinding)
        val destination = f.site(40, ValueRole.PropertyAssignment)
        val steps =
            listOf(
                QueryImpactStep.Compiler(f.edge(producer, binding, ValueTransferKind.LOCAL_BINDING)),
                QueryImpactStep.Compiler(f.edge(copy ?: binding, destination, ValueTransferKind.PROPERTY_ASSIGNMENT)),
            )
        val terminal =
            QueryImpactTerminal.Unresolved.Flow(
                ValueFlowObligation(destination, ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            )
        return (QueryImpactPath.fromEvidence(producer, steps, QueryImpactRepresentation.NotModeled, terminal)
                as Refinement.Refined)
            .value
    }
}
