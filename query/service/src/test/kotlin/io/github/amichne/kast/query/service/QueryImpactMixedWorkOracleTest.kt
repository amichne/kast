package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryTerminalMeaning
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Assumed detached facts exercise the real coordinator, without a native compiler or memory claim. */
class QueryImpactMixedWorkOracleTest {
    @Test
    fun `six mixed outputs remain pending when only five branch units remain`() = runTest {
        val c = MixedCase()
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                c.f.service(c.native.port).run(c.f.request(work = 1 + 5, plan = c.plan, checkpoint = c.checkpoint)),
            )
        assertTrue((result.result.rows as QueryRows.ValuePaths).values.isEmpty())
        assertEquals(QueryTerminalReason.NO_PROGRESS, (result.continuation as QueryContinuationState.Terminal).reason)
        assertEquals(1L, (result.workUsage as QueryWorkUsage.Observed).count.value)
        assertTrue(c.checkpoint.impact!!.paths.isEmpty())
        assertEquals(listOf(c.parent, PipelineTask.ImpactFinalize), c.checkpoint.tasks)
        c.native.assertConsumed()
    }

    @Test
    fun `exactly six branch units create two children and four distinct terminal paths`() = runTest {
        val c = MixedCase()
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                c.f.service(c.native.port).run(c.f.request(work = 1 + 6, plan = c.plan, checkpoint = c.checkpoint)),
            )
        val saved = (result.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val snapshot = checkNotNull(saved.impact)
        val children = saved.tasks.filterIsInstance<PipelineTask.ImpactExplore>()
        assertEquals(listOf(c.case.consumption, c.case.result), children.map { it.route.site })
        assertEquals(QueryImpactStep.Compiler(c.edge), children[0].route.steps.last())
        assertEquals(QueryImpactStep.ModeledValue(c.case.application), children[1].route.steps.last())
        assertEquals(4, snapshot.paths.size)
        assertEquals(2, snapshot.paths.count { it.terminal is QueryImpactTerminal.ModeledTerminal })
        val external = snapshot.paths.single { it.terminal is QueryImpactTerminal.Unresolved.Flow }
        assertEquals(c.obligation, (external.terminal as QueryImpactTerminal.Unresolved.Flow).obligation)
        val rejected = snapshot.paths.single { it.terminal is QueryImpactTerminal.Unresolved.ReadRejected }
        assertEquals(c.rejection, (rejected.terminal as QueryImpactTerminal.Unresolved.ReadRejected).rejection)
        assertSame(c.observation, (snapshot.reads.getValue(c.case.receiver) as ValueFlowRead.Observed).step)
        assertEquals(7L, (result.workUsage as QueryWorkUsage.Observed).count.value)
        c.native.assertConsumed()
    }

    private class MixedCase {
        val case = AutomaticValueQueryCase()
        val f = case.fixture
        val native = f.script(emptyList())
        val edge = f.edge(case.receiver, case.consumption, ValueTransferKind.ARGUMENT)
        val obligation = ValueFlowObligation(case.receiver, ValueFlowUnsupportedCause.EXTERNAL_CALL)
        val rejection =
            QueryImpactReadRejection.Native(
                case.receiver,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
                ValueFlowRejection.AUTHORITY_MOVED,
                RelationWorkCount.parse(1).autoRefined(),
            )
        val plan = f.plan(boundaries = listOf(terminal("first"), terminal("second")))
        val observation =
            ValueFlowStep.fromCompiler(
                    source = case.receiver,
                    transfers = listOf(edge),
                    obligations = listOf(obligation),
                    terminal = ValueFlowTerminal.Unresolved,
                    domain =
                        RelationRequest.start(
                            f.owner,
                            RelationMeaning.References,
                            RelationBudget(
                                f.request().budget.resources,
                                RelationByteLimit.parse(1000000).autoRefined(),
                            ),
                            RelationSearchBoundary.WORKSPACE_EXPANSION,
                        ),
                    examinedWorkUnits = RelationWorkCount.parse(1).autoRefined(),
                    applications = listOf(case.application),
                )
                .autoRefined()
        val parent =
            PipelineTask.ImpactExplore(
                QueryImpactRoute(
                    f.producer.site,
                    listOf(QueryImpactStep.Compiler(case.intoReceiver)),
                    QueryImpactRepresentation.NotModeled,
                )
            )
        val checkpoint =
            PipelineCheckpoint(
                PipelineSeed.Accounted.create(plan),
                f.lease,
                listOf(parent, PipelineTask.ImpactFinalize),
                emptyMap(),
                QueryJoinSnapshot(emptyMap()),
                emptySet(),
                QueryCount.parse(0).autoRefined(),
                QueryImpactSnapshot(
                    reads = mapOf(case.receiver to ValueFlowRead.Observed(observation)),
                    readRejections = mapOf(case.receiver to rejection),
                ),
            )

        private fun terminal(name: String): BoundaryModel.Terminal {
            val version = ModelVersion.parse(1).autoRefined()
            val identity = ContractModelIdentity(id(name), version, id("review:mixed-work"))
            val position =
                BoundaryPosition.at(
                    case.receiver,
                    BoundaryKind.PERSISTENCE,
                    BoundaryContractIdentity(id(name), version),
                    id("value"),
                )
            return BoundaryModel.Terminal.admit(
                    ModelRuleReference(identity, id("terminal")),
                    position.reference,
                    position,
                    BoundaryTerminalMeaning.REVIEWED_RETENTION,
                )
                .autoRefined()
        }

        private fun id(raw: String) = ModelIdentifier.parse(raw).autoRefined()
    }
}
