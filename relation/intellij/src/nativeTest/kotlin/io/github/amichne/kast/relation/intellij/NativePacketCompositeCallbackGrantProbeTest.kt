package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.runBlocking

/** This diagnostic locates a fixed qualified grant. Its capture alone makes no semantic pass claim. */
class NativePacketCompositeCallbackGrantProbeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-composite-callback-grant-jdk"

    fun testOriginalS2CharacterizesQualifiedCallbackGrants() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val argument = originalArgument(fixture)
        val complete =
            read(fixture = fixture, source = argument, budget = fixture.budget, observation = GrantAnalysisCounter())
        assertTrue(
            "Full-budget callback setup did not return an observed result: $complete",
            complete is ValueFlowRead.Observed,
        )
        val step = (complete as ValueFlowRead.Observed).step
        assertEquals(listOf(ValueTransferKind.SOURCE_CALL_ENTRY), step.transfers.map { it.kind })
        for (limit in FIRST_GRANT..LAST_GRANT) {
            val budget =
                fixture.budget.copy(
                    resources =
                        fixture.budget.resources.copy(workUnitLimit = WorkUnitLimit.parse(limit).nativeRefined())
                )
            val observation = GrantAnalysisCounter()
            record(
                limit,
                read(fixture = fixture, source = argument, budget = budget, observation = observation),
                observation,
            )
        }
        println("CALLBACK_GRANT_RESULT status=CHARACTERIZED semantic_assertion=NOT_RUN")
    }

    private fun originalArgument(fixture: NativePacketValueFixture): ValueSite {
        val request =
            fixture.selection(
                PacketDeclaration(NativePacketCompositeSuccessSource.CLIENT, "OffersClientImpl", "readOffers"),
                "execute",
                PacketDeclaration(NativePacketCompositeSuccessSource.WIRE, "Call", "execute"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }
        assertTrue("Original S2 seed prerequisite failed: $result", result is ValueProducerSeedRead.Seeded)
        val seed = (result as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.invocation.range)
        val receiver = next(fixture, seed.site, ValueTransferKind.RECEIVER)
        val formal = next(fixture, receiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        val fields = observed(fixture, formal)
        val value =
            fixture.argumentExpression(
                PacketDeclaration(NativePacketCompositeSuccessSource.OUTCOME, "Outcome", "map"),
                "transform",
                "value",
            )
        val success =
            fields.transfers.single {
                it.kind == ValueTransferKind.FIELD_READ && it.target.range == value.anchor
            }
        return next(fixture, success.target, ValueTransferKind.ARGUMENT).also {
            assertTrue(it.role is ValueRole.FunctionArgument)
        }
    }

    private fun next(fixture: NativePacketValueFixture, source: ValueSite, kind: ValueTransferKind): ValueSite {
        val step = observed(fixture, source)
        assertEquals(
            "Original S2 prerequisite frontier=${step.obligations.map { it.cause }}",
            listOf(kind),
            step.transfers.map { it.kind },
        )
        return step.transfers.single().target
    }

    private fun observed(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val value =
            read(fixture = fixture, source = source, budget = fixture.budget, observation = GrantAnalysisCounter())
        assertTrue("Original S2 prerequisite read failed: $value", value is ValueFlowRead.Observed)
        return (value as ValueFlowRead.Observed).step
    }

    private fun read(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        budget: RelationBudget,
        observation: GrantAnalysisCounter,
    ): ValueFlowRead = runBlocking {
        IntellijValueFlowCompilerAdapter(observation)
            .read(
                project = project,
                current = fixture.lease,
                request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                model = fixture.model,
            )
    }

    private fun record(limit: Long, result: ValueFlowRead, observation: GrantAnalysisCounter) {
        when (result) {
            is ValueFlowRead.Observed -> {
                val step = result.step
                val flows = step.obligations.mapNotNull { it.evidence as? ValueFlowObligationEvidence.CallbackFlow }
                println(
                    "CALLBACK_GRANT limit=$limit work=${step.examinedWorkUnits.value} k2=${observation.sessions} " +
                        "entries=${step.transfers.map { it.kind }} frontier=${step.obligations.map { it.cause }} " +
                        "flows=${flows.map { it.flow.invocations.size to it.flow.scan }}"
                )
            }
            is ValueFlowRead.Rejected ->
                println("CALLBACK_GRANT limit=$limit setup_rejected=${result.cause} k2=${observation.sessions}")
            is ValueFlowRead.ContractRejected ->
                println("CALLBACK_GRANT limit=$limit contract_rejected=$result k2=${observation.sessions}")
            is ValueFlowRead.Suspended ->
                println("CALLBACK_GRANT limit=$limit suspended=$result k2=${observation.sessions}")
        }
    }

    private companion object {
        const val FIRST_GRANT = 1L
        const val LAST_GRANT = 16L
    }
}

private class GrantAnalysisCounter : IntellijReadObservation {
    var sessions = 0
        private set

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        if (call == IntellijReadCall.K2_ANALYSIS) sessions++
        return IntellijReadCallScope.None
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
}
