package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRetentionFailureDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalDiagnosticCliDocuments
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticRetentionSchemaTest {
    private val schema = LiveReadOutputSchemaTest()

    @Test
    fun `actual diagnostic retention failure satisfies installed and public result schemas`() =
        with(schema) {
            val output = ProtocolText.parse("diagnostic-output:v1:00000000-0000-0000-0000-000000000001").refined()
            for (detail in ToolOutputDetail.entries) {
                for (continuation in listOf(null, output)) {
                    val document = projection(detail, continuation = continuation)
                    assertEquals(
                        JsonPrimitive("capacity_exceeded"),
                        document.getValue("qualification").jsonObject["retentionFailure"],
                    )
                    assertAdmits(CanonicalOperation.DIAGNOSTIC_CHECK, document)
                    assertTrue(McpStructuredResults.validates("check_diagnostics", document))
                }
            }
        }

    @Test
    fun `diagnostic result schemas reject unknown and null retention failures`() =
        with(schema) {
            val document = projection(ToolOutputDetail.VERBOSE)
            val qualification = document.getValue("qualification").jsonObject
            for (invalid in listOf(JsonNull, JsonPrimitive("UNKNOWN"))) {
                val malformed = document.with("qualification", qualification.with("retentionFailure", invalid))
                assertRejects(CanonicalOperation.DIAGNOSTIC_CHECK, malformed)
                assertFalse(McpStructuredResults.validates("check_diagnostics", malformed))
            }
        }

    @Test
    fun `diagnostic retention failure remains optional when no retention loss occurred`() =
        with(schema) {
            for (detail in ToolOutputDetail.entries) {
                val document = projection(detail, retentionFailure = null)
                assertFalse(document.getValue("qualification").jsonObject.containsKey("retentionFailure"))
                assertAdmits(CanonicalOperation.DIAGNOSTIC_CHECK, document)
                assertTrue(McpStructuredResults.validates("check_diagnostics", document))
            }
        }

    private fun projection(
        detail: ToolOutputDetail,
        continuation: ProtocolText? = null,
        retentionFailure: DiagnosticRetentionFailureDocument? = DiagnosticRetentionFailureDocument.CAPACITY_EXCEEDED,
    ): JsonObject =
        with(schema) {
            val qualification =
                DiagnosticCheckQualification.create(
                        knownDiagnosticCount = DiagnosticKnownCountDocument.parse(0).refined(),
                        resultLimitReached = true,
                        analyzedFiles = emptyList(),
                        limitations = emptyList(),
                        continuation = continuation,
                        retentionFailure = retentionFailure,
                    )
                    .refined()
            CanonicalDiagnosticCliDocuments.project(
                    OperationOutcome.Qualified(
                        EvidenceEnvelope(
                            CanonicalOperation.DIAGNOSTIC_CHECK.id,
                            EvidenceBasis.Live(
                                LiveReadEvidence.create(
                                        "/workspace",
                                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                                        1,
                                        LiveReadContentView.SAVED_PSI_COMMITTED,
                                        1,
                                    )
                                    .refined()
                            ),
                            DiagnosticCheckResult(
                                BoundedProtocolList.create(emptyList<DiagnosticDocument>()).refined()
                            ),
                        ),
                        qualification,
                    )
                )
                .document(detail)
        }
}
