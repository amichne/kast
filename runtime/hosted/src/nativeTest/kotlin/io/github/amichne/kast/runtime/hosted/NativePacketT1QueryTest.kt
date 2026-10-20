package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import kotlinx.coroutines.runBlocking

/** Both domains use the original S1 seed with the same real production and test roots present. */
class NativePacketT1QueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testOriginalS1ProductionReportsTheExactKnownEchoExclusion() = runBlocking {
        val fixture = prepare()
        val facts = fixture.t1Facts()
        val known = fixture.source.t1KnownEchoDeclaration()
        assertEquals(facts.echo, known.declaration)
        val paths =
            capture(
                fixture = fixture,
                required = facts.s1.apiBody,
                policy = NativePacketCompositeQueryPolicy.PRODUCTION,
                knownDeclarations = listOf(known),
            )
        NativePacketT1DispatchOracle(facts).assertProductionKnownEchoExclusion(paths)
        NativePacketS1QueryOracle(facts.s1).assertProductionForbiddenWitnesses(paths)
    }

    fun testOriginalS1ProductionWithoutAKnownDeclarationKeepsTheExactSelectedDomain() = runBlocking {
        val fixture = prepare()
        val facts = fixture.t1Facts()
        val paths = capture(fixture, facts.s1.apiBody, NativePacketCompositeQueryPolicy.PRODUCTION)
        NativePacketT1DispatchOracle(facts).assertProductionWithoutKnownDeclaration(paths)
        NativePacketS1QueryOracle(facts.s1).assertProductionForbiddenWitnesses(paths)
    }

    fun testOriginalS1CombinedEchoReachesTheFullGuardedFeatureReturn() = runBlocking {
        val fixture = prepare()
        val facts = fixture.t1Facts()
        NativePacketT1QueryOracle(facts)
            .assertCombinedFullEchoRoute(
                capture(fixture, facts.s1.common.getOfferReturn, NativePacketCompositeQueryPolicy.COMBINED)
            )
    }

    fun testCombinedWitnessesKeepProductionOpaqueAndForbidEchoFieldAndLogSubstitution() = runBlocking {
        val fixture = prepare()
        val facts = fixture.t1Facts()
        NativePacketT1QueryOracle(facts)
            .assertCombinedForbiddenWitnesses(
                capture(fixture, facts.s1.common.getOfferReturn, NativePacketCompositeQueryPolicy.COMBINED)
            )
    }

    private fun prepare(): NativePacketCompositeQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("packet-t1-query-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativePacketQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepareCompositeWithEcho()
    }

    private suspend fun capture(
        fixture: NativePacketCompositeQueryCase,
        required: ImpactValueSiteReferenceDocument,
        policy: NativePacketCompositeQueryPolicy,
        knownDeclarations: List<QueryImpactDeclarationDocument> = emptyList(),
    ) =
        NativePacketQueryCapture(fixture.services)
            .paths(
                producer = fixture.source.producerS1(),
                targets = listOf(required),
                outgoing = true,
                declarations = fixture.source.s1Declarations() + knownDeclarations,
                domain = policy.domain,
            )
}
