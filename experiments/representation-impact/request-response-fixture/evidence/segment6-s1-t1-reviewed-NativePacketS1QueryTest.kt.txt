package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import kotlinx.coroutines.runBlocking

/** The exact original constructor inside sequential starts both production query cases. */
class NativePacketS1QueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testOriginalS1RowsC11ThroughC14ReachTheExactOpaqueBodyArgument() = runBlocking {
        val fixture = prepare()
        val facts = fixture.s1Facts()
        NativePacketS1QueryOracle(facts).assertOriginalRequestRowsThroughC14(capture(fixture, facts))
    }

    fun testOriginalS1ProductionWitnessesForbidResponseLogAndFieldSubstitution() = runBlocking {
        val fixture = prepare()
        val facts = fixture.s1Facts()
        NativePacketS1QueryOracle(facts).assertProductionForbiddenWitnesses(capture(fixture, facts))
    }

    private fun prepare(): NativePacketCompositeQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("packet-s1-query-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativePacketQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepareComposite()
    }

    private suspend fun capture(fixture: NativePacketCompositeQueryCase, facts: NativePacketS1QueryFacts) =
        NativePacketQueryCapture(fixture.services)
            .paths(
                producer = fixture.source.producerS1(),
                targets = listOf(facts.apiBody),
                outgoing = true,
                declarations = fixture.source.s1Declarations(),
            )
}
