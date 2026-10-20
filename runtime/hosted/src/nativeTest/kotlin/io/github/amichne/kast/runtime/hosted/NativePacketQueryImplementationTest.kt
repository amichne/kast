package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import kotlinx.coroutines.runBlocking

/** V1 uses the same real query and retention owners as V0 with an exact implementation-qualified frame. */
class NativePacketQueryImplementationTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testInterfaceConstructorOutgoingKeepsTheConcreteTargetThroughRetention() = runBlocking {
        val fixture = prepare()
        val oracle = NativePacketQueryOracle.from(fixture.source)
        val paths = capture(fixture, oracle, true)
        assertTrue(
            "V1 original constructor lacks its full outgoing field and exact implementation proof: " +
                "${oracle.describe(paths)}",
            oracle.outgoingRoute(paths),
        )
        val forbidden = oracle.outgoingForbidden(paths)
        assertTrue("V1 emitted forbidden response witnesses: $forbidden", forbidden.isEmpty())
    }

    fun testInterfaceIncomingReturnKeepsTheConcreteTargetThroughRetention() = runBlocking {
        val fixture = prepare()
        val oracle = NativePacketQueryOracle.from(fixture.source)
        val paths = capture(fixture, oracle, false)
        assertTrue(
            "V1 send result lacks its full incoming field and exact implementation proof: ${oracle.describe(paths)}",
            oracle.incomingRoute(paths),
        )
        val forbidden = oracle.incomingForbidden(paths)
        assertTrue("V1 emitted forbidden request witnesses: $forbidden", forbidden.isEmpty())
    }

    private fun prepare(): NativePacketQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk =
                JavaSdk.getInstance()
                    .createJdk("packet-query-implementation-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativePacketQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepare(NativePacketQueryVariant.V1)
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
