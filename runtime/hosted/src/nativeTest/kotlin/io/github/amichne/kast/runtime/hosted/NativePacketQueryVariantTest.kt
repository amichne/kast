package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import kotlinx.coroutines.runBlocking

/** Every plain variant uses its frozen constructor and send seeds through the existing production query coordinator. */
class NativePacketQueryVariantTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testV2OutgoingPreservesReceiverHelperFieldsAndMatchedReturn() = runBlocking {
        outgoing(NativePacketQueryVariant.V2)
    }

    fun testV2IncomingPreservesReceiverHelperFieldsAndMatchedReturn() = runBlocking {
        incoming(NativePacketQueryVariant.V2)
    }

    fun testV3NestedConstructorOutgoingReachesTheExactTransportArgument() = runBlocking {
        outgoing(NativePacketQueryVariant.V3)
    }

    fun testV3NestedSendIncomingReachesTheExactFeatureReturn() = runBlocking {
        incoming(NativePacketQueryVariant.V3)
    }

    fun testV8DestinationIndexOneReachesSendIndexZero() = runBlocking {
        outgoing(NativePacketQueryVariant.V8)
    }

    fun testV8IncomingReachesTheExactFeatureReturn() = runBlocking {
        incoming(NativePacketQueryVariant.V8)
    }

    private suspend fun outgoing(variant: NativePacketQueryVariant) {
        val fixture = prepare(variant)
        val oracle = NativePacketQueryOracle.from(fixture.source)
        val send = oracle.outgoingOccurrence.role as ImpactValueRoleDocument.Argument
        assertEquals(packetQueryOffset(0), send.index)
        if (variant == NativePacketQueryVariant.V8)
            assertEquals(packetQueryOffset(1), fixture.source.propertyIndex("WireRequest", "destination"))
        val paths = capture(fixture, oracle, true)
        assertTrue(
            "$variant lacks its full outgoing source route: ${oracle.describe(paths)}",
            oracle.outgoingRoute(paths),
        )
        val forbidden = oracle.outgoingForbidden(paths)
        assertTrue("$variant emitted forbidden response-return witnesses: $forbidden", forbidden.isEmpty())
    }

    private suspend fun incoming(variant: NativePacketQueryVariant) {
        val fixture = prepare(variant)
        val oracle = NativePacketQueryOracle.from(fixture.source)
        val paths = capture(fixture, oracle, false)
        assertTrue(
            "$variant lacks its full incoming source route: ${oracle.describe(paths)}",
            oracle.incomingRoute(paths),
        )
        val forbidden = oracle.incomingForbidden(paths)
        assertTrue("$variant emitted forbidden request-argument witnesses: $forbidden", forbidden.isEmpty())
    }

    private fun prepare(variant: NativePacketQueryVariant): NativePacketQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("packet-query-variant-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativePacketQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepare(variant)
    }

    private suspend fun capture(fixture: NativePacketQueryCase, oracle: NativePacketQueryOracle, outgoing: Boolean) =
        NativePacketQueryCapture(fixture.services)
            .paths(
                producer = fixture.source.producer(outgoing),
                targets = listOf(if (outgoing) oracle.outgoingOccurrence else oracle.incomingOccurrence),
                outgoing = outgoing,
                declarations = fixture.source.requestedDeclarations(outgoing),
            )
}
