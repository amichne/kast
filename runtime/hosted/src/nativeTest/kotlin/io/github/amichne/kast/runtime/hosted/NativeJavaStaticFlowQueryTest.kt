package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import kotlinx.coroutines.runBlocking

class NativeJavaStaticFlowQueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testMixedSourceRunCompletesAndPublishesTheExactConsumer() = runBlocking {
        val fixture = prepare()
        val result = capture(fixture, "direct", "direct")
        assertTrue(result.outcome.toString(), result.outcome is OperationOutcome.Complete)
        assertConsumerRoute(fixture = fixture, result = result, host = "direct", method = "direct")
    }

    fun testUnsupportedSiblingRejectsRunAndRetainsEvidenceOnly() = runBlocking {
        val fixture = prepare()
        val result = capture(fixture, "sibling", "relay")
        val outcome = result.outcome
        assertTrue(outcome.toString(), outcome is OperationOutcome.Rejected)
        assertTrue((outcome as OperationOutcome.Rejected).reason is QueryRunRejection.CompletionUnproven)
        assertTrue(result.retained.interpretation is QueryResultInterpretationDocument.EvidenceOnly)
        assertConsumerRoute(fixture = fixture, result = result, host = "sibling", method = "relay")
        val blocked = result.paths.map { it.terminal }.filterIsInstance<ImpactPathTerminalDocument.UnresolvedFlow>()
        assertEquals(ImpactFlowUnsupportedDocument.UNMODELED_CALL, blocked.single().cause)
        val unsupported = fixture.source.invocation("", "sibling", "branch")
        assertTrue(
            result.paths
                .flatMap { it.steps.values }
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .none { step ->
                    step.transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
                        step.transfer.target.range == unsupported
                }
        )
    }

    fun testKotlinV1KeepsTheMixedSourceContinuationUnsupported() = runBlocking {
        val fixture = prepare()
        val capture = NativePacketQueryCapture(fixture.services)
        val outcome =
            capture.execute(
                capture.request(
                    producer = fixture.source.producer("", "direct", "producer"),
                    targets = emptyList(),
                    declarations = emptyList(),
                    domain = packetQueryDomain(),
                )
            )
        assertTrue(outcome.toString(), outcome is OperationOutcome.Rejected)
        assertTrue((outcome as OperationOutcome.Rejected).reason is QueryRunRejection.CompletionUnproven)
    }

    private suspend fun capture(fixture: NativePacketQueryCase, host: String, method: String): NativePacketQueryResult =
        NativePacketQueryCapture(fixture.services, flow = QueryImpactFlowDocument.KOTLIN_JAVA_FORWARD_V2)
            .capture(
                producer = fixture.source.producer("", host, "producer"),
                targets = listOf(consumer(fixture.source, host, method)),
                outgoing = false,
                declarations =
                    listOf(
                        fixture.source.requestedDeclaration("", host),
                        fixture.source.requestedDeclaration("", "consume"),
                    ),
            )

    private suspend fun consumer(
        source: NativePacketQuerySource,
        host: String,
        method: String,
    ): ImpactValueSiteReferenceDocument {
        val range = source.invocation("", host, method)
        return ImpactValueSiteReferenceDocument(
            enclosing = source.reference(source.declaration("", host)),
            range = range,
            role =
                ImpactValueRoleDocument.Argument(
                    ImpactInvocationReferenceDocument(
                        source.invocationWithArgument(host, "consume", range),
                        source.reference(source.declaration("", "consume")),
                    ),
                    packetQueryOffset(0),
                ),
        )
    }

    private suspend fun assertConsumerRoute(
        fixture: NativePacketQueryCase,
        result: NativePacketQueryResult,
        host: String,
        method: String,
    ) {
        val expected = consumer(fixture.source, host, method)
        val accounting = result.retained.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(ImpactFlowSemanticsDocument.KOTLIN_JAVA_FORWARD_V2, accounting.semantics)
        assertTrue(
            result.paths.any { path ->
                val transfers =
                    path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
                transfers.any {
                    it.kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN && it.target.range == expected.range
                } && transfers.any { it.kind == ImpactTransferKindDocument.ARGUMENT && it.target == expected }
            }
        )
    }

    private fun prepare(): NativePacketQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("java-static-flow-query-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativePacketQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepare(NativePacketQueryVariant.JAVA_FLOW)
    }
}
