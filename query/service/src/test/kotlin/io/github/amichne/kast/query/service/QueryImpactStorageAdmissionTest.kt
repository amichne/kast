package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
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
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactStorageAdmissionTest {
    @Test
    fun `dense repeated callable evidence retains every flow obligation within default checkpoint capacity`() =
        runTest {
            val f = QueryImpactExecutionFixture()
            val owner = RelationEndpoint.subject(SymbolSelector.issue(f.lease, f.scope, f.owner.evidence))
            val binding = denseSite(owner, 30, 31, ValueRole.LocalBinding)
            val locals = (0 until 150).map { denseSite(owner, 35 + it, 36 + it, ValueRole.LocalRead) }
            val arguments = denseArguments(f, owner)
            val expectations = denseExpectations(f, binding, locals, arguments)
            val native = f.unorderedScript(expectations)
            val result =
                assertInstanceOf(
                    QueryExecutionResult.Qualified::class.java,
                    f.service(native.port)
                        .run(
                            f.request(
                                work = 1000,
                                results = 100,
                                checkpointBytes = QueryByteLimit.DefaultCheckpoint.value,
                                returnedBytes = 64000000,
                            )
                        ),
                )
            val captured =
                (QueryRetainedResult.captureInvestigation(f.lease, result) as Refinement.Refined).value
                    as QueryRetainedResult.ValuePaths
            val rows = captured.rows
            assertEquals(100, (result.result.rows as QueryRows.ValuePaths).values.size)
            assertEquals(150, rows.values.size)
            assertTrue(
                rows.values.all {
                    it.terminal is QueryImpactTerminal.Unresolved.Flow
                }
            )
            assertEquals(arguments.map { it.identity }, rows.values.map { it.destination.identity })
            native.assertConsumed()
            assertDenseRetention(captured, result)
        }

    private fun assertDenseRetention(captured: QueryRetainedResult.ValuePaths, result: QueryExecutionResult.Qualified) {
        val ledger = (captured.rows.accounting as QueryValuePathAccounting.Investigated).ledger
        val ledgerBytes = QueryImpactRetainedGraph().ledger(ledger)
        assertTrue(ledgerBytes <= QueryByteLimit.DefaultCheckpoint.value, "ledger=$ledgerBytes")
        val checkpoint = (result.continuation as QueryContinuationState.Resumable).checkpoint
        assertSame(checkpoint, (captured.producerProgress as QueryContinuationState.Resumable).checkpoint)
        val terminal =
            result.copy(continuation = QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE))
        val withoutCheckpoint =
            (QueryRetainedResult.captureInvestigation(captured.lease, terminal) as Refinement.Refined).value
        assertEquals(
            checkpoint.retainedBytes,
            captured.retainedBytes - withoutCheckpoint.retainedBytes,
            "A checkpoint carries its existing conservative charge through retention",
        )
        val pairedBytes = captured.retainedBytes + checkpoint.retainedBytes
        val limit = ReadLimits.Default[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value
        assertTrue(
            pairedBytes <= limit,
            "ledger=$ledgerBytes retained=${captured.retainedBytes} " +
                "checkpoint=${checkpoint.retainedBytes} paired=$pairedBytes limit=$limit",
        )
    }

    private fun denseSite(owner: RelationEndpoint, start: Int, end: Int, role: ValueRole): ValueSite =
        (ValueSite.fromCompiler(owner, (ExactDeclarationTextRange.parse(start, end) as Refinement.Refined).value, role)
                as Refinement.Refined)
            .value

    private fun denseArguments(f: QueryImpactExecutionFixture, owner: RelationEndpoint): List<ValueSite> =
        (0 until 150).map {
            val fresh = f.call("display", 34 + it, 39 + it)
            val invocation =
                (ValueInvocation.fromCompiler(owner, fresh.range, fresh.callable) as Refinement.Refined).value
            denseSite(
                owner,
                35 + it,
                36 + it,
                ValueRole.Argument(invocation, (ValueArgumentPosition.parse(0) as Refinement.Refined).value),
            )
        }

    private fun denseExpectations(
        f: QueryImpactExecutionFixture,
        binding: ValueSite,
        locals: List<ValueSite>,
        arguments: List<ValueSite>,
    ): List<ImpactReadExpectation> =
        listOf(
            ImpactReadExpectation(
                f.producer.site,
                listOf(f.edge(f.producer.site, binding, ValueTransferKind.LOCAL_BINDING)),
            ),
            ImpactReadExpectation(binding, locals.map { f.edge(binding, it, ValueTransferKind.LOCAL_READ) }),
        ) +
            locals.zip(arguments).flatMap { (local, argument) ->
                listOf(
                    ImpactReadExpectation(local, listOf(f.edge(local, argument, ValueTransferKind.ARGUMENT))),
                    ImpactReadExpectation(argument, causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL)),
                )
            }

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
