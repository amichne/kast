package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import kotlinx.coroutines.runBlocking

/** These cases join original K2 facts, the production query scheduler, and its retained evidence. */
class NativePacketQueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testPrimaryConstructorPropertyKeepsItsExactCompilerIdentity() = runBlocking {
        val fixture = prepare()
        val ordinary = fixture.source.declaration("BackendClient", "fetch")
        assertEquals(CompilerSymbolKind.FUNCTION, ordinary.kind)
        val parameter = fixture.source.ordinaryParameter("BackendClient", "fetch", "request")
        assertEquals(
            SymbolResolutionCompilation.Rejected(SymbolExactCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE),
            parameter,
        )
        val field = fixture.source.field("ClientRequest", "destination")
        assertEquals(fixture.source.propertyRange("ClientRequest", "destination"), field.range)
        assertEquals(fixture.source.reference(ordinary).file, field.file)
        assertEquals(fixture.source.reference(ordinary).basis, field.basis)
    }

    fun testOriginalConstructorOutgoingReachesTheExactTransportArgument() = runBlocking {
        val fixture = prepare()
        val oracle = NativePacketQueryOracle.from(fixture.source)
        val paths = capture(fixture, oracle, true)
        assertTrue(
            "Original constructor lacks its full outgoing field/context route: ${oracle.describe(paths)}",
            oracle.outgoingRoute(paths),
        )
    }

    fun testOpaqueSendIncomingReachesTheExactFeatureResponseField() = runBlocking {
        val fixture = prepare()
        val oracle = NativePacketQueryOracle.from(fixture.source)
        val paths = capture(fixture, oracle, false)
        assertTrue(
            "Opaque send result lacks its full incoming field/context route: ${oracle.describe(paths)}",
            oracle.incomingRoute(paths),
        )
    }

    fun testOutgoingEmittedWitnessesDoNotInventTheResponseReturn() = runBlocking {
        val fixture = prepare()
        val oracle = NativePacketQueryOracle.from(fixture.source)
        val forbidden = oracle.outgoingForbidden(capture(fixture, oracle, true))
        assertTrue("Forbidden outgoing witnesses were emitted: $forbidden", forbidden.isEmpty())
    }

    fun testIncomingEmittedWitnessesDoNotInventRequestArguments() = runBlocking {
        val fixture = prepare()
        val oracle = NativePacketQueryOracle.from(fixture.source)
        val forbidden = oracle.incomingForbidden(capture(fixture, oracle, false))
        assertTrue("Forbidden incoming witnesses were emitted: $forbidden", forbidden.isEmpty())
    }

    private fun prepare(): NativePacketQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("packet-query-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativePacketQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepare()
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
