package io.github.amichne.kast.relation.intellij

import com.intellij.testFramework.DumbModeTestUtils
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueNativeOperation
import io.github.amichne.kast.relation.contract.ValueNativePrerequisite
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import kotlinx.coroutines.runBlocking

class NativeValueFlowReadinessTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "value-flow-readiness-jdk"

    fun testIndexesUnavailableRejectBeforeNativeFlowWithRequiredReadinessEvidence() {
        val fixture = packetFixture(listOf(CONTROLS))
        val adapter = IntellijValueFlowCompilerAdapter()
        val selection =
            fixture.selection(
                PacketDeclaration(CONTROLS, "", "reorderedComponents"),
                "destructuringOrigin",
                PacketDeclaration(CONTROLS, "", "destructuringOrigin"),
            )
        val seed =
            runBlocking {
                adapter.seed(project = project, current = fixture.lease, request = selection, model = fixture.model)
            }
                as ValueProducerSeedRead.Seeded
        val read =
            DumbModeTestUtils.computeInDumbModeSynchronously(project) {
                runBlocking {
                    adapter.read(
                        project = project,
                        current = fixture.lease,
                        request =
                            ValueFlowRequest(seed.seed.site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                        model = fixture.model,
                    )
                }
            } as ValueFlowRead.Rejected
        assertEquals(
            ValueFlowRejection.Native.HostUnavailable(
                ValueNativeOperation.FLOW_READ,
                ValueNativePrerequisite.INDEXES_UNAVAILABLE,
            ),
            read.cause,
        )
        assertEquals(0L, read.examinedWorkUnits.value)
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
    }
}
