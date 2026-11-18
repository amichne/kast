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
import io.github.amichne.kast.protocol.contract.ImpactPathClassificationDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import kotlinx.coroutines.runBlocking

private const val BUCKET_DIVISOR = 4

class NativeJavaStaticFlowQueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testMixedSourceRunCompletesAndPublishesTheExactConsumer() = runBlocking {
        val fixture = prepare()
        val result = capture(fixture, "direct", "direct")
        assertTrue(result.outcome.toString(), result.outcome is OperationOutcome.Complete)
        assertConsumerRoute(fixture = fixture, result = result, host = "direct", method = "direct")
    }

    fun testIntDivisionReachesTheExactConsumerAsDerived() = runBlocking {
        val fixture = prepare()
        val result = capture(fixture, "bucketed", "bucket")
        assertTrue(result.outcome.toString(), result.outcome is OperationOutcome.Complete)
        assertConsumerRoute(fixture = fixture, result = result, host = "bucketed", method = "bucket")
        val expected = consumer(fixture.source, "bucketed", "bucket")
        val path =
            result.paths.single { path ->
                path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().any {
                    it.transfer.target == expected
                }
            }
        assertEquals(
            ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.DERIVED),
            path.classification,
        )
        val division =
            path.steps.values
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .single { it.transfer.kind == ImpactTransferKindDocument.PRIMITIVE_DERIVATION }
                .transfer
        val proof = division.evidence as ImpactTransferEvidenceDocument.JavaIntDivision
        assertOperandRanges(proof)
        assertEquals(BUCKET_DIVISOR, proof.divisor)
        assertEquals(division.source.range, proof.inputRange)
        assertEquals(division.target.range, proof.resultRange)
        assertEquals(division.source.callStack, proof.callStack)
        assertEquals(division.source.enclosing, proof.execution.enclosing)
        assertFalse(division.copy(evidence = proof.copy(divisor = 0)).admitsEvidence())
        assertFalse(division.copy(evidence = proof.copy(divisorRange = proof.inputRange)).admitsEvidence())
        val unrelated = consumer(fixture.source, "unrelatedBucket", "bucket")
        assertFalse(
            result.paths
                .flatMap { it.steps.values }
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .any { it.transfer.target == unrelated }
        )
        assertDivisionAccounting(fixture, result, expected)
    }

    private fun assertOperandRanges(proof: ImpactTransferEvidenceDocument.JavaIntDivision) {
        val java =
            checkNotNull(javaClass.getResourceAsStream("/impact-boundary/JavaFlow.java")).bufferedReader().use {
                it.readText()
            }
        val prefix = "static int bucket(int raw) {\n        return "
        val start = java.indexOf(prefix) + prefix.length
        assertTrue(java.indexOf(prefix) >= 0 && java.indexOf(prefix) == java.lastIndexOf(prefix))
        assertEquals(reviewedQueryRange(start, start + "raw".length), proof.inputRange)
        assertEquals(reviewedQueryRange(start + "raw / ".length, start + "raw / 4".length), proof.divisorRange)
        assertEquals(reviewedQueryRange(start, start + "raw / 4".length), proof.resultRange)
    }

    private suspend fun assertDivisionAccounting(
        fixture: NativePacketQueryCase,
        result: NativePacketQueryResult,
        expected: ImpactValueSiteReferenceDocument,
    ) {
        val reference = checkNotNull(result.retained.presentationWindow).reference
        val reads = fixture.services.nativeFlowReads
        val snapshot = fixture.services.observation.snapshot()
        val proofReads = fixture.services.nativeProofReads
        val reader = NativePacketQueryCapture(fixture.services)
        val page =
            reader.execute(
                QueryRunRequest.ReadResult.impactWitness(reference, ImpactWitnessSectionDocument.SITE_ACCOUNTING)
            ) as OperationOutcome.Complete
        val item = page.evidence.payload.items.values.single() as QueryResultItemDocument.ImpactWitness
        val accounting = (item.item.witness as ImpactWitnessDocument.SiteAccounting).accounting
        assertEquals(expected, accounting.site)
        assertTrue(accounting.outcome is ImpactSiteOutcomeDocument.Reached)
        assertEquals(reads, fixture.services.nativeFlowReads)
        assertEquals(proofReads, fixture.services.nativeProofReads)
        assertEquals(snapshot, fixture.services.observation.snapshot())
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
