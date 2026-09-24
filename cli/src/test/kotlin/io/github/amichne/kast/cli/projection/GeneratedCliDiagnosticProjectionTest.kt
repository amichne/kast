package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticInventoryDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.diagnosticCheckCliProjector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Diagnostic projections preserve analysis kind and exact file coverage. */
class GeneratedCliDiagnosticProjectionTest {
    @Test
    fun `diagnostic projection reports exact analyzed file coverage`() {
        val progress =
            DiagnosticProgressDocument(
                stage = DiagnosticProgressStage.FINISHED,
                inventory = DiagnosticInventoryDocument.Exhausted(ProtocolCount.parse(1).refined()),
                analyzedFiles = listOf(text("src/A.kt")),
                stop = DiagnosticProgressStop.FINISHED,
                knownDiagnosticCount = DiagnosticKnownCountDocument.parse(0).refined(),
                requestedPath = text("src/A.kt"),
            )
        val outcome =
            OperationOutcome.Complete(
                evidence(CanonicalOperation.DIAGNOSTIC_CHECK, DiagnosticCheckResult(bounded(emptyList()), progress))
            )
        val projected = diagnosticCheckCliProjector.project(outcome) as ProjectedOperationOutcome.Complete
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        assertEquals("IDE_FILE_DIAGNOSTICS", document.getValue("analysisKind").jsonPrimitive.content)
        val coverage = document.getValue("coverage").jsonObject
        assertEquals("src/A.kt", coverage.getValue("requestedPath").jsonPrimitive.content)
        assertEquals(1, coverage.getValue("filesDiscovered").jsonPrimitive.int)
        assertEquals(1, coverage.getValue("filesAnalyzed").jsonPrimitive.int)
        assertEquals(0, coverage.getValue("filesSkipped").jsonPrimitive.int)
        assertTrue(coverage.getValue("exhaustive").jsonPrimitive.boolean)
    }

    @Test
    fun `unfinished empty diagnostic scan does not assert exhaustive absence`() {
        val progress =
            DiagnosticProgressDocument(
                stage = DiagnosticProgressStage.ANALYSIS,
                inventory = DiagnosticInventoryDocument.Exhausted(ProtocolCount.parse(2).refined()),
                analyzedFiles = listOf(text("src/A.kt")),
                stop = DiagnosticProgressStop.ANALYSIS_PENDING,
                knownDiagnosticCount = DiagnosticKnownCountDocument.parse(0).refined(),
                requestedPath = text("src"),
            )
        val qualification =
            DiagnosticCheckQualification.create(
                    DiagnosticKnownCountDocument.parse(0).refined(),
                    resultLimitReached = false,
                    analyzedFiles = listOf(text("src/A.kt")),
                    limitations =
                        listOf(
                            DiagnosticLimitationDocument(text("src/B.kt"), DiagnosticLimitationReasonDocument.INDEXING)
                        ),
                )
                .refined()
        val outcome =
            OperationOutcome.Qualified(
                evidence(CanonicalOperation.DIAGNOSTIC_CHECK, DiagnosticCheckResult(bounded(emptyList()), progress)),
                qualification,
            )
        val projected = diagnosticCheckCliProjector.project(outcome) as ProjectedOperationOutcome.Qualified
        val coverage = Json.parseToJsonElement(projected.document.value).jsonObject.getValue("coverage").jsonObject
        assertEquals(2, coverage.getValue("filesDiscovered").jsonPrimitive.int)
        assertEquals(1, coverage.getValue("filesAnalyzed").jsonPrimitive.int)
        assertEquals(JsonNull, coverage.getValue("filesSkipped"))
        assertEquals(false, coverage.getValue("exhaustive").jsonPrimitive.boolean)
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value> evidence(operation: CanonicalOperation, value: Value): EvidenceEnvelope<Value> =
        EvidenceEnvelope(operation.id, EvidenceGeneration.parse(1).refined(), value)

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
