package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.readAction
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import kotlinx.coroutines.runBlocking

/** Phase capture and adapter preservation use separate fixed-grant cases on the original S2 source. */
class NativePacketCallbackGrantSixTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-callback-grant-six-jdk"

    fun testOriginalS2GrantSixCapturesTheRealCallbackPhaseBeforeAdapterMapping() = runBlocking {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val expected = NativePacketCompositeSuccessSource(project, getOrCreateProjectBaseDir(), fixture)
        val helper = NativePacketCallbackGrantFixture(project, fixture, expected)
        val argument = helper.originalArgument()
        val request = ValueFlowRequest(argument, helper.budget(GRANT), RelationSearchBoundary.RETAINED_SUBJECT)
        readAction { capturePhase(fixture = fixture, expected = expected, request = request) }
    }

    fun testOriginalS2GrantSixKeepsTheExactWorkCauseAndEmitsNoCallbackEntry() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val expected = NativePacketCompositeSuccessSource(project, getOrCreateProjectBaseDir(), fixture)
        val helper = NativePacketCallbackGrantFixture(project, fixture, expected)
        val argument = helper.originalArgument()
        val result = helper.read(argument, helper.budget(GRANT))
        val summary = summary(result)
        println("CALLBACK_GRANT6_ADAPTER $summary")
        assertTrue(
            "Grant6 must preserve semantic work evidence instead of rejecting the site: $summary",
            result is ValueFlowRead.Observed,
        )
        val step = (result as ValueFlowRead.Observed).step
        assertTrue("A work-exhausted callback acquired an entry", step.transfers.isEmpty())
        assertEquals(listOf(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED), step.obligations.map { it.cause })
        val raw = step.obligations.mapNotNull { it.evidence as? ValueFlowObligationEvidence.CallbackUnavailable }
        assertEquals(listOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED), raw.map { it.cause })
        assertTrue(step.obligations.all { it.site == argument })
        assertEquals(GRANT, step.examinedWorkUnits.value)
        assertEquals(ValueFlowTerminal.Unresolved, step.terminal)
    }

    private fun capturePhase(
        fixture: NativePacketValueFixture,
        expected: NativePacketCompositeSuccessSource,
        request: ValueFlowRequest,
    ) {
        val domain = RelationRequest.start(request.source.enclosing, RelationMeaning.References, request.budget)
        val prepared = IntellijValueFlowCompilerAdapter().prepare(project, domain, fixture.model)
        assertTrue(
            "Real callback phase setup failed: $prepared",
            prepared is IntellijValueFlowCompilerAdapter.Prepared.Ready,
        )
        val ready = prepared as IntellijValueFlowCompilerAdapter.Prepared.Ready
        val allowance = IntellijRelationAllowance { System.nanoTime() }
        allowance.examine()
        assertEquals(CallbackWorkAdmission.READY, allowance.admitCallbackWork(request.budget.resources))
        assertEquals(OUTER_WORK, allowance.examined)
        val retained =
            NativeValueFlowTransfers(
                request = request,
                owner = ready.owner,
                scope = ready.scope,
                observation = IntellijReadObservation.None,
            )
        val observation =
            NativeValueCallbackFlow(
                    request = request,
                    role = request.source.role as ValueRole.FunctionArgument,
                    scope = ready.scope,
                    projection = ready.projection,
                    allowance = allowance,
                    retained = retained,
                    permit = { error("observe must not start callback entry work") },
                )
                .observe()
        recordObservation(observation, request.source, expected)
        println(
            "CALLBACK_GRANT6_PHASE work=${allowance.examined} entries=${retained.edges.size} " +
                "ledger=${retained.obligations.size}"
        )
        assertTrue(retained.edges.isEmpty())
        assertTrue(retained.obligations.isEmpty())
    }

    private fun recordObservation(
        observation: NativeValueCallbackObservation,
        source: ValueSite,
        expected: NativePacketCompositeSuccessSource,
    ) {
        assertTrue(
            "The admitted supply did not reach callback proof: $observation",
            observation is NativeValueCallbackObservation.Read,
        )
        val read = observation as NativeValueCallbackObservation.Read
        assertEquals(source.callStack.frames.last(), read.supply.supplyingFrame)
        assertEquals(expected.implicitIt().body, range(read.supply.literal))
        recordCallback(source, read.callback)
    }

    private fun recordCallback(source: ValueSite, read: CallbackInvocationFlowRead) {
        when (read) {
            is CallbackInvocationFlowRead.Observed -> recordFlow(source, read.flow)
            is CallbackInvocationFlowRead.Unavailable ->
                println("CALLBACK_GRANT6_PHASE callback=UNAVAILABLE cause=${read.cause}")
            is CallbackInvocationFlowRead.ContractRejected ->
                println("CALLBACK_GRANT6_PHASE callback=CONTRACT_REJECTED cause=${read.cause}")
            is CallbackInvocationFlowRead.Immutable -> println("CALLBACK_GRANT6_PHASE callback=IMMUTABLE")
        }
    }

    private fun recordFlow(source: ValueSite, flow: CallbackInvocationFlow) {
        val unavailable = (flow.binding as? CallbackBindingEvidence.Unavailable)?.cause
        val invocation = (source.role as ValueRole.FunctionArgument).call
        val selected =
            flow.invocations.count {
                it.occurrence.range == invocation.range && it.occurrence.file == source.enclosing.file
            }
        println(
            "CALLBACK_GRANT6_PHASE callback=OBSERVED binding=${binding(flow.binding)} " +
                "rawCause=$unavailable scan=${flow.scan} invocations=${flow.invocations.size} selected=$selected " +
                "causes=${flow.obligations}"
        )
        when (val admitted = ValueFlowObligation.fromCallback(source, flow)) {
            is Refinement.Refined ->
                println(
                    "CALLBACK_GRANT6_PHASE central=REFINED cause=${admitted.value.cause} " +
                        "exactSource=${admitted.value.site == source}"
                )
            is Refinement.Rejected -> println("CALLBACK_GRANT6_PHASE central=REJECTED cause=${admitted.failure}")
        }
        println("CALLBACK_GRANT6_PHASE status=CHARACTERIZED cause_assertion=NOT_RUN")
    }

    private fun summary(result: ValueFlowRead): String =
        when (result) {
            is ValueFlowRead.Observed ->
                "variant=OBSERVED work=${result.step.examinedWorkUnits.value} " +
                    "entries=${result.step.transfers.size} causes=${result.step.obligations.map { it.cause }}"
            is ValueFlowRead.Rejected -> "variant=REJECTED cause=${result.cause} work=${result.examinedWorkUnits.value}"
            is ValueFlowRead.ContractRejected ->
                "variant=CONTRACT_REJECTED cause=${result.cause} work=${result.examinedWorkUnits.value}"
            is ValueFlowRead.Suspended ->
                "variant=SUSPENDED cause=${result.cause} work=${result.step.examinedWorkUnits.value}"
        }

    private fun binding(evidence: CallbackBindingEvidence): GrantBinding =
        when (evidence) {
            is CallbackBindingEvidence.Bound -> GrantBinding.BOUND
            is CallbackBindingEvidence.Default -> GrantBinding.DEFAULT
            is CallbackBindingEvidence.Direct -> GrantBinding.DIRECT
            is CallbackBindingEvidence.DependencyContract -> GrantBinding.DEPENDENCY_CONTRACT
            is CallbackBindingEvidence.DeferredDependency -> GrantBinding.DEFERRED_DEPENDENCY
            is CallbackBindingEvidence.Unavailable -> GrantBinding.UNAVAILABLE
        }

    private companion object {
        const val GRANT = 6L
        const val OUTER_WORK = 2L
    }
}

private enum class GrantBinding {
    BOUND,
    DEFAULT,
    DIRECT,
    DEPENDENCY_CONTRACT,
    DEFERRED_DEPENDENCY,
    UNAVAILABLE,
}
