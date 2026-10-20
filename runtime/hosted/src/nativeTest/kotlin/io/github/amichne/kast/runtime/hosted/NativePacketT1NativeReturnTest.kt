package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueCallbackTarget
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** One original public run supplies the native return proof independently of retained presentation. */
class NativePacketT1NativeReturnTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testOriginalS1CombinedNativeReturnPreservesIssuedHostBeforeRetention() = runBlocking {
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
        val publication = NativePacketT1PublicationCapture()
        val outcome =
            NativePacketQueryCapture(fixture.services, publication)
                .run(
                    producer = fixture.source.producerS1(),
                    targets = listOf(facts.s1.common.getOfferReturn),
                    declarations = fixture.source.s1Declarations(),
                    domain = NativePacketCompositeQueryPolicy.COMBINED.domain,
                )
        publication.record(outcome)
        val (request, read) = captured.returned()
        val field = (request.source.valuePath.segments.single() as ValuePathSegment.Field).field.declaration
        assertEquals(facts.s1.common.featureLink.file.value, field.file.stableValue)
        assertEquals(facts.s1.common.featureLink.compilerIdentity.value, field.compilerIdentity.value)
        val source = NativePacketT1SeedCallbackSource(fixture.source)
        source.prove(request)
        val publicFlow = captured.callback(fixture.services, request)
        val publicBinding = (publicFlow.binding as CallbackBindingEvidence.Bound).binding
        source.proveBoundSupplier(publicBinding)
        val oracle = NativePacketT1SeedCallbackOracle(issued)
        oracle.assertSourceFacts(request, publicFlow)
        oracle.assertScopeTransport(request, read)
        assertWrongPublicHostIsNotEmitted(read, publicBinding.invocation.enclosing)
        publication.assertExpectedPublicQualification()
        assertTrue(publication.admissions.isNotEmpty())
        println("T1_NATIVE_ONLY_ASSERTION source=Return1029:1057 target=ExpressionResult872:897 publicGoal=NOT_PASSED")
    }

    private fun assertWrongPublicHostIsNotEmitted(read: ValueFlowRead, publicHost: RelationEndpoint) {
        val transfer =
            (read as ValueFlowRead.Observed).step.transfers.single {
                it.kind == ValueTransferKind.SOURCE_CALL_RETURN
            }
        val evidence = (transfer.evidence as? ValueTransferEvidence.Guarded)?.delegate ?: transfer.evidence
        val operation = (evidence as ValueSourceTransfer).operation as ValueSourceOperation.SeedCallbackReturn
        val proof = operation.frame.target as ValueCallbackTarget
        val bound = (proof.flow.binding as CallbackBindingEvidence.Bound).binding.invocation
        assertFalse(
            "A public callback-universe host cannot replace the exact issued host",
            bound.enclosing.hasSameProofAs(publicHost),
        )
        assertFalse(proof.supplyingFrame.invocation.enclosing.hasSameProofAs(publicHost))
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
