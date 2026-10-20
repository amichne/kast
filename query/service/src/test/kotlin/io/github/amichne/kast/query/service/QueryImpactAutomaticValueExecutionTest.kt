package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.RepresentationCurrent
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationUnknownReason
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValueSite
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The real query coordinator consumes strict scripted port reads. These cases establish no native compiler behavior.
 */
class QueryImpactAutomaticValueExecutionTest {
    @Test
    fun `automatic receiver model reaches opaque sink without supplied models or representation state`() = runTest {
        val case = AutomaticValueQueryCase()
        val native = case.fixture.script(modeledReads(case))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                case.fixture.service(native.port).run(case.fixture.request(plan = case.fixture.plan())),
            )
        val rows = assertInstanceOf(QueryRows.ValuePaths::class.java, result.result.rows)
        val path = rows.values.single()
        assertModelPath(case, path)
        assertEquals(QueryImpactRepresentation.NotModeled, path.representation)
        assertExternalAt(path, case.consumption)
        assertEquals(listOf(case.fixture.producer.site, case.receiver, case.result, case.consumption), native.examined)
        assertEquals(4, (rows.accounting as QueryValuePathAccounting.Investigated).ledger.observations.size)
        native.assertConsumed()
    }

    @Test
    fun `automatic receiver model preserves the origin history while weakening known representation`() = runTest {
        val case = AutomaticValueQueryCase()
        val native = case.fixture.script(modeledReads(case))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                case.fixture
                    .service(native.port)
                    .run(case.fixture.request(plan = case.fixture.plan(models = listOf(case.originRule)))),
            )
        val path = (result.result.rows as QueryRows.ValuePaths).values.single()
        assertModelPath(case, path)
        val evidence = (path.representation as QueryImpactRepresentation.Present).evidence
        assertEquals(case.consumption, evidence.site)
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.UNMODELED_TRANSFORMATION),
            evidence.branches.single().current,
        )
        assertEquals(
            case.origin.branches.single().history +
                listOf(
                    RepresentationHistory.CompilerTransfer(case.intoReceiver),
                    case.application,
                    RepresentationHistory.CompilerTransfer(case.intoSink),
                ),
            evidence.branches.single().history,
        )
        assertExternalAt(path, case.consumption)
        assertEquals(listOf(case.fixture.producer.site, case.receiver, case.result, case.consumption), native.examined)
        native.assertConsumed()
    }

    @Test
    fun `opaque receiver without an application stops before a result read`() = runTest {
        val case = AutomaticValueQueryCase()
        val native =
            case.fixture.script(
                listOf(
                    ImpactReadExpectation(case.fixture.producer.site, edges = listOf(case.intoReceiver)),
                    ImpactReadExpectation(case.receiver, causes = listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL)),
                )
            )
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                case.fixture.service(native.port).run(case.fixture.request()),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        val path = rows.values.single()
        assertEquals(listOf(QueryImpactStep.Compiler(case.intoReceiver)), path.steps)
        assertEquals(QueryImpactRepresentation.NotModeled, path.representation)
        assertExternalAt(path, case.receiver)
        assertEquals(listOf(case.fixture.producer.site, case.receiver), native.examined)
        assertEquals(2, (rows.accounting as QueryValuePathAccounting.Investigated).ledger.observations.size)
        native.assertConsumed()
    }

    @Test
    fun `automatic application does not erase an independent external receiver obligation`() = runTest {
        val case = AutomaticValueQueryCase()
        val native =
            case.fixture.script(modeledReads(case, receiverCauses = listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL)))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                case.fixture.service(native.port).run(case.fixture.request()),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        assertEquals(2, rows.values.size)
        val receiverPath = rows.values.single { path -> path.destination == case.receiver }
        assertExternalAt(receiverPath, case.receiver)
        assertEquals(listOf(QueryImpactStep.Compiler(case.intoReceiver)), receiverPath.steps)
        val modeled = rows.values.single { path -> path.destination == case.consumption }
        assertModelPath(case, modeled)
        assertExternalAt(modeled, case.consumption)
        assertTrue(rows.values.all { path -> path.representation == QueryImpactRepresentation.NotModeled })
        assertEquals(listOf(case.fixture.producer.site, case.receiver, case.result, case.consumption), native.examined)
        assertEquals(4, (rows.accounting as QueryValuePathAccounting.Investigated).ledger.observations.size)
        native.assertConsumed()
    }

    private fun modeledReads(
        case: AutomaticValueQueryCase,
        receiverCauses: List<ValueFlowUnsupportedCause> = emptyList(),
    ) =
        listOf(
            ImpactReadExpectation(case.fixture.producer.site, edges = listOf(case.intoReceiver)),
            ImpactReadExpectation(case.receiver, causes = receiverCauses, applications = listOf(case.application)),
            ImpactReadExpectation(case.result, edges = listOf(case.intoSink)),
            ImpactReadExpectation(case.consumption, causes = listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL)),
        )

    private fun assertModelPath(case: AutomaticValueQueryCase, path: QueryImpactPath) {
        assertEquals(case.fixture.producer.site, path.producer)
        assertEquals(
            listOf(
                QueryImpactStep.Compiler(case.intoReceiver),
                QueryImpactStep.ModeledValue(case.application),
                QueryImpactStep.Compiler(case.intoSink),
            ),
            path.steps,
        )
        val application = (path.steps[1] as QueryImpactStep.ModeledValue).application
        assertEquals(case.application, application)
        assertEquals(case.receiver, application.source)
        assertEquals(case.result, application.target)
        assertEquals(case.invocation, application.invocation)
        val selection = application as ValueModelApplication.CollectionSelection
        assertEquals(case.receiver, selection.condition.receiver)
        assertEquals(case.result, selection.condition.result)
        assertEquals(ValueAccessPath.Whole, selection.condition.receiver.valuePath)
        assertEquals(ValueAccessPath.Whole, selection.condition.result.valuePath)
        assertEquals(0, selection.condition.index.value)
    }

    private fun assertExternalAt(path: QueryImpactPath, expected: ValueSite) {
        val terminal = assertInstanceOf(QueryImpactTerminal.Unresolved.Flow::class.java, path.terminal)
        assertEquals(expected, path.destination)
        assertEquals(expected, terminal.obligation.site)
        assertEquals(ValueFlowUnsupportedCause.EXTERNAL_CALL, terminal.obligation.cause)
    }
}
