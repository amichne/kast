package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import kotlinx.coroutines.runBlocking

/** Original S2 keeps its seed and names the first missing source operation. */
class NativePacketCompositeSuccessPathTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-composite-success-prefix-jdk"

    fun testOriginalS2ImplicitReadMapsThePayloadAndReconstructsSuccess() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val request =
            fixture.selection(
                PacketDeclaration(NativePacketCompositeSuccessSource.CLIENT, "OffersClientImpl", "readOffers"),
                "execute",
                PacketDeclaration(NativePacketCompositeSuccessSource.WIRE, "Call", "execute"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue("Original S2 admission failed before C2.1: $result", result is ValueProducerSeedRead.Seeded)
        val seed = (result as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.invocation.range)
        assertEquals(
            request.expectedCallable.compilerIdentity,
            (seed.origin as ValueProducerOrigin.Declared).invocation.callable.compilerIdentity,
        )
        val source = NativePacketCompositeSuccessSource(project, getOrCreateProjectBaseDir(), fixture)
        val route = NativePacketCompositeSuccessRoute(project, fixture, source)
        val entered = route.enteredCallback(seed.site)
        val mapped = route.clientResponse(entered)
        val returned = route.callbackReturn(entered, mapped)
        route.reconstructedSuccess(entered, returned)
        println(
            "COMPOSITE_RESULT rows=C2.1,C2.2 stage=success-construction " +
                "remaining=C2.5-return,C2.6-C2.9 status=NOT_RUN"
        )
    }
}
