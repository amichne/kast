package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import kotlinx.coroutines.runBlocking

/** K2 operator identity and supplemental expression admission remain separate from original S2 producer admission. */
class NativePacketDestructuringProbeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-destructuring-probe-jdk"

    fun testOriginalResponseDestructuringCapturesTheActualComponentCallAndReceivers() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val source = NativePacketDestructuringProbeSource(project, getOrCreateProjectBaseDir(), fixture)
        ReadAction.run<RuntimeException> { source.capture() }
    }

    fun testOriginalResponseInitializerCapturesSupplementalAdapterAdmission() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val source = NativePacketDestructuringProbeSource(project, getOrCreateProjectBaseDir(), fixture)
        val expected = ReadAction.compute<DestructuringProbeExpectation, RuntimeException> { source.expectation() }
        val domain =
            RelationRequest.start(
                RelationEndpoint.subject(expected.enclosing),
                RelationMeaning.References,
                fixture.budget,
            )
        val adapter = IntellijValueFlowCompilerAdapter()
        val admitted = runBlocking {
            adapter.resolve(
                project = project,
                current = fixture.lease,
                enclosing = expected.enclosing,
                anchor = expected.initializer,
                domain = domain,
                model = fixture.model,
            )
        }
        when (admitted) {
            is ValueSiteResolution.Rejected ->
                println("DESTRUCTURING_INITIALIZER admission=REJECTED cause=${admitted.cause}")
            is ValueSiteResolution.Found -> {
                assertEquals(expected.initializer, admitted.site.range)
                assertEquals(expected.executionOwner, admitted.site.executionOwner)
                val read = runBlocking {
                    adapter.read(
                        project = project,
                        current = fixture.lease,
                        request =
                            ValueFlowRequest(admitted.site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                        model = fixture.model,
                    )
                }
                record(read)
            }
        }
        println(
            "DESTRUCTURING_INITIALIZER_RESULT status=CHARACTERIZED original_S2_admission=NOT_RUN full_S2_route=NOT_RUN"
        )
    }

    private fun record(read: ValueFlowRead) {
        when (read) {
            is ValueFlowRead.Observed ->
                println(
                    "DESTRUCTURING_INITIALIZER admission=FOUND read=OBSERVED " +
                        "work=${read.step.examinedWorkUnits.value} transfers=${read.step.transfers.map { it.kind }} " +
                        "causes=${read.step.obligations.map { it.cause }}"
                )
            is ValueFlowRead.Rejected ->
                println(
                    "DESTRUCTURING_INITIALIZER read=REJECTED cause=${read.cause} work=${read.examinedWorkUnits.value}"
                )
            is ValueFlowRead.ContractRejected ->
                println(
                    "DESTRUCTURING_INITIALIZER read=CONTRACT_REJECTED cause=${read.cause} " +
                        "work=${read.examinedWorkUnits.value}"
                )
            is ValueFlowRead.Suspended ->
                println(
                    "DESTRUCTURING_INITIALIZER read=SUSPENDED cause=${read.cause} " +
                        "work=${read.step.examinedWorkUnits.value}"
                )
        }
    }
}
