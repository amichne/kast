package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Wider fixed grants characterize a selected qualified flow. No grant derives its own success condition. */
class NativePacketCallbackWiderGrantProbeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-callback-wider-grant-jdk"

    fun testOriginalS2CharacterizesWiderQualifiedCallbackGrantsAndFullWork() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val expected = NativePacketCompositeSuccessSource(project, getOrCreateProjectBaseDir(), fixture)
        val helper = NativePacketCallbackGrantFixture(project, fixture, expected)
        val argument = helper.originalArgument()
        val fullCounter = WiderGrantCounter()
        val full = helper.read(argument, observation = fullCounter)
        assertTrue("Full-grant callback prerequisite failed: $full", full is ValueFlowRead.Observed)
        val step = (full as ValueFlowRead.Observed).step
        assertEquals(listOf(ValueTransferKind.SOURCE_CALL_ENTRY), step.transfers.map { it.kind })
        recordStep(
            grant = fixture.budget.resources.workUnitLimit.value,
            step = step,
            source = argument,
            sessions = fullCounter.sessions,
        )
        for (grant in GRANTS) {
            val counter = WiderGrantCounter()
            val result = helper.read(source = argument, budget = helper.budget(grant), observation = counter)
            when (result) {
                is ValueFlowRead.Observed ->
                    recordStep(grant = grant, step = result.step, source = argument, sessions = counter.sessions)
                is ValueFlowRead.Rejected ->
                    println(
                        "CALLBACK_WIDER_GRANT grant=$grant rejected=${result.cause} " +
                            "work=${result.examinedWorkUnits.value}"
                    )
                is ValueFlowRead.ContractRejected ->
                    println(
                        "CALLBACK_WIDER_GRANT grant=$grant contractRejected=${result.cause} " +
                            "work=${result.examinedWorkUnits.value}"
                    )
                is ValueFlowRead.Suspended -> println("CALLBACK_WIDER_GRANT grant=$grant suspended=${result.cause}")
            }
        }
        println("CALLBACK_WIDER_GRANT_RESULT status=CHARACTERIZED ordering_assertion=NOT_RUN")
    }

    private fun recordStep(grant: Long, step: ValueFlowStep, source: ValueSite, sessions: Int) {
        val invocation = (source.role as ValueRole.FunctionArgument).call
        val flows = step.obligations.mapNotNull { it.evidence as? ValueFlowObligationEvidence.CallbackFlow }
        val raw = step.obligations.mapNotNull { it.evidence as? ValueFlowObligationEvidence.CallbackUnavailable }
        println(
            "CALLBACK_WIDER_GRANT grant=$grant work=${step.examinedWorkUnits.value} k2=$sessions " +
                "entries=${step.transfers.map { it.kind }} causes=${step.obligations.map { it.cause }} " +
                "unavailable=${raw.map { it.cause }}"
        )
        flows.forEach { evidence ->
            val flow = evidence.flow
            val selected =
                flow.invocations.count {
                    it.occurrence.range == invocation.range && it.occurrence.file == source.enclosing.file
                }
            val unavailable = (flow.binding as? CallbackBindingEvidence.Unavailable)?.cause
            println(
                "CALLBACK_WIDER_FLOW grant=$grant binding=${binding(flow.binding)} raw=$unavailable " +
                    "scan=${flow.scan} invocations=${flow.invocations.size} selected=$selected " +
                    "causes=${flow.obligations}"
            )
        }
    }

    private fun binding(evidence: CallbackBindingEvidence): WiderGrantBinding =
        when (evidence) {
            is CallbackBindingEvidence.Bound -> WiderGrantBinding.BOUND
            is CallbackBindingEvidence.Default -> WiderGrantBinding.DEFAULT
            is CallbackBindingEvidence.Direct -> WiderGrantBinding.DIRECT
            is CallbackBindingEvidence.DependencyContract -> WiderGrantBinding.DEPENDENCY_CONTRACT
            is CallbackBindingEvidence.DeferredDependency -> WiderGrantBinding.DEFERRED_DEPENDENCY
            is CallbackBindingEvidence.Unavailable -> WiderGrantBinding.UNAVAILABLE
        }

    private companion object {
        const val GRANT_24 = 24L
        const val GRANT_32 = 32L
        const val GRANT_48 = 48L
        const val GRANT_64 = 64L
        const val GRANT_96 = 96L
        const val GRANT_128 = 128L
        const val GRANT_192 = 192L
        const val GRANT_256 = 256L
        val GRANTS = listOf(GRANT_24, GRANT_32, GRANT_48, GRANT_64, GRANT_96, GRANT_128, GRANT_192, GRANT_256)
    }
}

private enum class WiderGrantBinding {
    BOUND,
    DEFAULT,
    DIRECT,
    DEPENDENCY_CONTRACT,
    DEFERRED_DEPENDENCY,
    UNAVAILABLE,
}

private class WiderGrantCounter : IntellijReadObservation {
    var sessions = 0
        private set

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        if (call == IntellijReadCall.K2_ANALYSIS) sessions++
        return IntellijReadCallScope.None
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
}
