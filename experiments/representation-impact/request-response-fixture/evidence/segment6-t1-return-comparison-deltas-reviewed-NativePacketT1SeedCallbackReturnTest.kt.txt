package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValuePathSegment
import kotlinx.coroutines.runBlocking

/** The original T1 constructor query supplies the actual failing return request. */
class NativePacketT1SeedCallbackReturnTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testOriginalS1CombinedReturnKeepsTheIssuedHostAndActualSupplierProof() = runBlocking {
        val fixture = prepare()
        val facts = fixture.t1Facts()
        val issued =
            RelationEndpoint.subject(
                fixture.source.declaration(
                    path = NativePacketCompositeQuerySource.FEATURE,
                    owner = "OffersProcessorImpl",
                    member = "getOffer",
                )
            )
        val captured = NativePacketT1SeedCallbackCapture(fixture.services.observation)
        fixture.services.captureNativeReadStart = captured::beforeRead
        fixture.services.captureNativeRead = captured::record
        val paths =
            NativePacketQueryCapture(fixture.services)
                .paths(
                    producer = fixture.source.producerS1(),
                    targets = listOf(facts.s1.common.getOfferReturn),
                    outgoing = true,
                    declarations = fixture.source.s1Declarations(),
                    domain = NativePacketCompositeQueryPolicy.COMBINED.domain,
                )
        assertTrue(paths.all { it.producer == facts.s1.origin })
        NativePacketT1QueryOracle(facts).assertCombinedForbiddenWitnesses(paths)
        val (request, read) = captured.returned()
        val field = (request.source.valuePath.segments.single() as ValuePathSegment.Field).field.declaration
        assertEquals(facts.s1.common.featureLink.file.value, field.file.stableValue)
        assertEquals(facts.s1.common.featureLink.compilerIdentity.value, field.compilerIdentity.value)
        val source = NativePacketT1SeedCallbackSource(fixture.source)
        source.prove(request)
        val flow = captured.callback(fixture.services, request)
        val binding = (flow.binding as CallbackBindingEvidence.Bound).binding
        source.proveBoundSupplier(binding)
        val oracle = NativePacketT1SeedCallbackOracle(issued)
        oracle.assertSourceFacts(request, flow)
        oracle.assertScopeTransport(request, read)
    }

    private fun prepare(): NativePacketCompositeQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("packet-t1-seed-return-sdk", System.getProperty("java.home"), false)
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
}
