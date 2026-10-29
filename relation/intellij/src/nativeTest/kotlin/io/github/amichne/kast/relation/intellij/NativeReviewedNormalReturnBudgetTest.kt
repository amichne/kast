package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.readAction
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.runBlocking

class NativeReviewedNormalReturnBudgetTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-normal-return-budget-jdk"

    fun testSpentSharedWorkPreventsNormalReturnAnalysis() = assertGuard(NormalReturnCutoff.WORK)

    fun testExpiredSharedTimePreventsNormalReturnAnalysis() = assertGuard(NormalReturnCutoff.TIME)

    fun testAvailableSharedPermitKeepsNormalReceiverResult() = assertGuard(NormalReturnCutoff.NONE)

    private fun assertGuard(cutoff: NormalReturnCutoff) = runBlocking {
        val fixture = fixture()
        val source =
            trace(fixture, declaration("reviewedAlso"), declaration("siblingPayload"))
                .flatMap { it.reviewedTransfers }
                .single()
                .source
        val request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT)
        val resources = request.budget.resources as ResourceBudget.Bounded
        val clock = NormalReturnClock()
        val allowance = IntellijRelationAllowance { clock.nanoseconds }
        val observation = NormalReturnBudgetObservation(cutoff, resources, allowance, clock)
        readAction {
            val relation =
                RelationRequest.start(
                    subject = source.enclosing,
                    meaning = RelationMeaning.References,
                    budget = fixture.budget,
                    boundary = RelationSearchBoundary.RETAINED_SUBJECT,
                )
            val scope =
                (IntellijRelationScopeCompiler()
                        .compile(
                            project = project,
                            request = relation,
                            modelCompilation = fixture.model,
                            observation = observation,
                        ) as IntellijRelationScopeCompilation.Compiled)
                    .scope
            val projection = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot, observation)
            val host = projection.subject(scope, source.enclosing) as IntellijRelationSubjectLookup.Found
            val retained =
                NativeValueFlowTransfers(
                    request = request,
                    owner = host.declaration,
                    scope = scope,
                    observation = observation,
                )
            nativeReviewedReceiverResults(request, scope, projection, allowance, retained)
            assertEquals("The actual declared callback admission must run once", 1, observation.declaredReturns)
            assertNormalReturnResult(cutoff, observation, retained)
        }
    }

    private fun assertNormalReturnResult(
        cutoff: NormalReturnCutoff,
        observation: NormalReturnBudgetObservation,
        retained: NativeValueFlowTransfers,
    ) {
        if (cutoff == NormalReturnCutoff.NONE) {
            assertTrue(observation.analysesAfterAdmission > 0)
            assertEquals(1, retained.reviewedEdges.size)
            assertTrue(retained.obligations.isEmpty())
        } else {
            assertEquals("A refused current permit forbids later K2 analysis", 0, observation.analysesAfterAdmission)
            assertTrue("A refused current permit forbids a reviewed receiver result", retained.reviewedEdges.isEmpty())
            val cause =
                when (cutoff) {
                    NormalReturnCutoff.WORK -> ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
                    NormalReturnCutoff.TIME -> ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
                    NormalReturnCutoff.NONE -> error("The admitted branch returned already")
                }
            assertEquals(listOf(cause), retained.obligations.map { it.cause })
        }
    }
}

private enum class NormalReturnCutoff {
    NONE,
    WORK,
    TIME,
}

private class NormalReturnClock {
    var nanoseconds = 0L
}

private class NormalReturnBudgetObservation(
    private val cutoff: NormalReturnCutoff,
    private val resources: ResourceBudget.Bounded,
    private val allowance: IntellijRelationAllowance,
    private val clock: NormalReturnClock,
) : IntellijReadObservation {
    var declaredReturns = 0
        private set

    var analysesAfterAdmission = 0
        private set

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        if (call == IntellijReadCall.K2_ANALYSIS && declaredReturns > 0) analysesAfterAdmission++
        return object : IntellijReadCallScope {
            override fun finish(outcome: IntellijReadCallOutcome) {
                check(outcome == IntellijReadCallOutcome.RETURNED)
                if (call == IntellijReadCall.VALUE_DECLARED_CALLBACK_ADMISSION) {
                    declaredReturns++
                    when (cutoff) {
                        NormalReturnCutoff.NONE -> Unit
                        NormalReturnCutoff.WORK ->
                            allowance.chargePreparation(
                                RelationWorkCount.parse(resources.workUnitLimit.value).nativeRefined()
                            )
                        NormalReturnCutoff.TIME ->
                            clock.nanoseconds = resources.elapsedTimeLimit.value * NANOS_PER_MILLISECOND
                    }
                }
            }
        }
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
