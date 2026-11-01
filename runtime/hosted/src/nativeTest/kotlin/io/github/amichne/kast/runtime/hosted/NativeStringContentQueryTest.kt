package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathClassificationDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
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
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Production queries keep derived relationships, exact requested sites, and null construction in retained evidence. */
class NativeStringContentQueryTest : NativeContainerQueryFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testRepeatedEntriesReachOneRequestedSiteWithDerivedFindings() = runBlocking {
        val fixture = containerFixture()
        val source = fixture.source
        val target = target(source, "string")
        val producer = source.producer("", "stringCase", "stringOrigin")
        val captured =
            NativeContainerQueryCapture(fixture.services)
                .capture(
                    producer,
                    listOf(target),
                    listOf(
                        fixture.source.requestedDeclaration("", "stringCase"),
                        fixture.source.requestedDeclaration("", "consumeString"),
                    ),
                )
        val content =
            captured.paths
                .flatMap { path -> path.steps.values }
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .map { it.transfer }
                .filter { it.kind == ImpactTransferKindDocument.STRING_CONTENT }
                .distinct()
        assertEquals(2, content.size)
        assertEquals(
            2,
            content.map { (it.evidence as ImpactTransferEvidenceDocument.StringContent).entryRange }.distinct().size,
        )
        assertEquals(1, content.map { it.target }.distinct().size)
        val accounting = witnesses(fixture.services, captured, ImpactWitnessSectionDocument.SITE_ACCOUNTING)
        val site = (accounting.single() as ImpactWitnessDocument.SiteAccounting).accounting
        assertEquals(target, site.site)
        assertTrue(site.outcome is ImpactSiteOutcomeDocument.Reached)
        assertDerivedFindings(witnesses(fixture.services, captured, ImpactWitnessSectionDocument.FINDINGS))
    }

    fun testSourceMemberConversionKeepsItsReturnRoleInTheEncodedQuery() = runBlocking {
        val fixture = containerFixture()
        val target = target(fixture.source, "object")
        val producer = fixture.source.producer("", "objectCase", "stringOrigin")
        val captured =
            NativeContainerQueryCapture(fixture.services)
                .capture(
                    producer,
                    listOf(target),
                    listOf(
                        fixture.source.requestedDeclaration("", "objectCase"),
                        fixture.source.requestedDeclaration("", "consumeString"),
                    ),
                )
        assertTrue(
            captured.paths
                .flatMap { it.steps.values }
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .any {
                    it.transfer.target.role is ImpactValueRoleDocument.StringConversionResult
                }
        )
        val member = fixture.source.reference(fixture.source.declaration("TextToken", "toString"))
        val calls =
            captured.paths
                .flatMap { it.steps.values }
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .mapNotNull { (it.transfer.target.role as? ImpactValueRoleDocument.StringConversionResult)?.invocation }
        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.callable == member })
        captured.paths.forEach { path ->
            val encoded = Json.encodeToString(ImpactPathDocument.serializer(), path)
            assertEquals(path, Json.decodeFromString(ImpactPathDocument.serializer(), encoded))
        }
        assertTrue(
            captured.paths.any { path ->
                Json.encodeToString(ImpactPathDocument.serializer(), path)
                    .contains("\"type\":\"STRING_CONVERSION_RESULT\"")
            }
        )
        assertTrue(
            captured.paths.all {
                it.classification == ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.DERIVED)
            }
        )
        assertDerivedFindings(witnesses(fixture.services, captured, ImpactWitnessSectionDocument.FINDINGS))
    }

    fun testNullConstructionSurvivesRetainedPathsAndNativeReads() = runBlocking {
        val fixture = containerFixture()
        val producer = fixture.source.producer("", "nullCase", "nullOrigin")
        val captured = NativeContainerQueryCapture(fixture.services).capture(producer)
        assertTrue(
            captured.paths
                .flatMap { it.steps.values }
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .none {
                    it.transfer.kind == ImpactTransferKindDocument.STRING_CONTENT
                }
        )
        val constructions =
            captured.paths
                .mapNotNull { (it.terminal as? ImpactPathTerminalDocument.SupportedDomainEnd)?.observation }
                .flatMap { it.stringConstructions.values }
        assertEquals(1, constructions.size)
        val reads =
            witnesses(fixture.services, captured, ImpactWitnessSectionDocument.NATIVE_READS)
                .filterIsInstance<ImpactWitnessDocument.NativeRead>()
        assertEquals(constructions, reads.flatMap { it.stringConstructions.values })
    }

    private suspend fun target(source: NativePacketQuerySource, scenario: String): ImpactValueSiteReferenceDocument =
        ImpactValueSiteReferenceDocument(
            enclosing = source.reference(source.declaration("", "${scenario}Case")),
            range = source.markedRange("$scenario-use"),
            role =
                ImpactValueRoleDocument.Argument(
                    ImpactInvocationReferenceDocument(
                        source.invocation("", "${scenario}Case", "consumeString"),
                        source.reference(source.declaration("", "consumeString")),
                        ImpactExecutionOwnerDocument.Declaration,
                    ),
                    packetQueryOffset(0),
                ),
        )

    private fun assertDerivedFindings(witnesses: List<ImpactWitnessDocument>) {
        val findings = witnesses.filterIsInstance<ImpactWitnessDocument.Finding>()
        assertTrue(findings.isNotEmpty())
        assertTrue(
            findings.all { finding ->
                finding.finding.classification ==
                    ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.DERIVED)
            }
        )
    }

    private suspend fun witnesses(
        services: NativePacketQueryServices,
        captured: NativePacketQueryResult,
        section: ImpactWitnessSectionDocument,
    ): List<ImpactWitnessDocument> {
        val reference = checkNotNull(captured.retained.presentationWindow).reference
        val flow = services.nativeFlowReads
        val proofs = services.nativeProofReads
        val compiler = services.observation.snapshot()
        val read =
            NativePacketQueryCapture(services).execute(QueryRunRequest.ReadResult.impactWitness(reference, section))
        assertEquals(flow, services.nativeFlowReads)
        assertEquals(proofs, services.nativeProofReads)
        assertEquals(compiler, services.observation.snapshot())
        assertTrue(read is OperationOutcome.Complete)
        return (read as OperationOutcome.Complete).evidence.payload.items.values.map {
            (it as QueryResultItemDocument.ImpactWitness).item.witness
        }
    }
}
