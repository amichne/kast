package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeRejection
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.contract.ChangeRunError
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticInventoryDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalDiagnosticCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ChangeRunCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompactToolPresentationTest {
    @Test
    fun `compact query retains qualification and exact continuation while verbose restores bookkeeping`() {
        val token = QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000001").value()
        val qualification = qualification(token)
        val outcome =
            CanonicalQueryCliDocuments.project(
                OperationOutcome.Qualified(
                    EvidenceEnvelope(
                        CanonicalOperation.QUERY_RUN.id,
                        basis(),
                        QueryRunResult(bounded(emptyList()), bounded(emptyList())),
                    ),
                    qualification,
                )
            ) as ProjectedOperationOutcome.Qualified
        val compact = json(outcome.document.present(ToolOutputDetail.COMPACT))
        val verbose = json(outcome.document.present(ToolOutputDetail.VERBOSE))
        assertEquals(verbose.getValue("qualification"), compact.getValue("qualification"))
        assertEquals(verbose.getValue("coverage"), compact.getValue("coverage"))
        assertEquals("54", compact.getValue("qualification").jsonObject.getValue("knownMinimum").jsonPrimitive.content)
        assertEquals(
            token.value,
            compact
                .getValue("qualification")
                .jsonObject
                .getValue("progress")
                .jsonObject
                .getValue("checkpoint")
                .jsonObject
                .getValue("token")
                .jsonPrimitive
                .content,
        )
        assertFalse("continuation" in compact)
        assertEquals(token.value, verbose.getValue("continuation").jsonPrimitive.content)
        assertFalse("failures" in compact)
        assertTrue("failures" in verbose)
        assertEquals(setOf("root", "contentView"), compact.getValue("live").jsonObject.keys)
        assertTrue("epoch" in verbose.getValue("live").jsonObject)
        assertEquals(
            verbose,
            json(outcome.document.present(ToolOutputDetail.COMPACT).present(ToolOutputDetail.VERBOSE)),
        )
    }

    @Test
    fun `compact verified mutation keeps receipt fresh reference and actual diff once`() {
        val phase = Json { encodeDefaults = true }.encodeToJsonElement(VerifiedFixture()).jsonObject
        val result = ChangeRunCliDocuments.project(ChangeRunDocument.Complete("plan:fixture", phase, phase))
        val compact = json(result.present(ToolOutputDetail.COMPACT))
        val verbose = json(result.present(ToolOutputDetail.VERBOSE))
        assertEquals(setOf("status", "planIdentity", "application"), compact.keys)
        assertEquals("complete", compact.getValue("status").jsonPrimitive.content)
        assertEquals(phase, compact.getValue("application"))
        assertEquals(phase, verbose.getValue("plan"))
        val rejected =
            ChangeRunCliDocuments.project(
                ChangeRunDocument.Rejected(
                    ChangeRunError(ChangeRejection.RECOVERY_UNAVAILABLE, "plan:fixture", phase, phase, phase)
                )
            )
        assertEquals(json(rejected.present(ToolOutputDetail.VERBOSE)), json(rejected.present(ToolOutputDetail.COMPACT)))
    }

    @Test
    fun `compact completed diagnostics keep exact coverage without duplicate progress`() {
        val path = ProtocolText.parse("Fixture.kt").value()
        val progress =
            DiagnosticProgressDocument(
                DiagnosticProgressStage.FINISHED,
                DiagnosticInventoryDocument.Exhausted(ProtocolCount.parse(1).value()),
                listOf(path),
                stop = DiagnosticProgressStop.FINISHED,
                knownDiagnosticCount = DiagnosticKnownCountDocument.parse(0).value(),
                requestedPath = path,
            )
        val outcome =
            CanonicalDiagnosticCliDocuments.project(
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.DIAGNOSTIC_CHECK.id,
                        basis(),
                        DiagnosticCheckResult(bounded(emptyList()), progress),
                    )
                )
            ) as ProjectedOperationOutcome.Complete
        val compact = json(outcome.document.present(ToolOutputDetail.COMPACT))
        val verbose = json(outcome.document.present(ToolOutputDetail.VERBOSE))
        assertFalse("progress" in compact)
        assertTrue("progress" in verbose)
        assertEquals(verbose.getValue("diagnostics"), compact.getValue("diagnostics"))
        assertEquals(verbose.getValue("coverage"), compact.getValue("coverage"))
        val coverage = compact.getValue("coverage").jsonObject
        assertEquals("true", coverage.getValue("exhaustive").jsonPrimitive.content)
        assertEquals("Fixture.kt", coverage.getValue("requestedPath").jsonPrimitive.content)
        assertEquals("1", coverage.getValue("filesAnalyzed").jsonPrimitive.content)
    }

    private fun qualification(token: QueryExecutionContinuation.Pipeline): QueryRunQualification =
        QueryRunQualification.create(
                QueryKnownMinimum.parse(54).value(),
                listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                QueryQualifiedProgressDocument.Resumable(
                    QueryCheckpointDocument.Upstream(token),
                    ReadResumeActionDocument.RESUME,
                ),
            )
            .value()

    private fun basis(): EvidenceBasis =
        EvidenceBasis.Live(
            LiveReadEvidence.create(
                    "/workspace",
                    UUID.fromString("b41c43b0-1f11-4ca9-9ec0-b6fc88cd31c4"),
                    7,
                    LiveReadContentView.SAVED_PSI_COMMITTED,
                    1,
                )
                .value()
        )

    private fun json(document: CanonicalJsonDocument): JsonObject = Json.parseToJsonElement(document.value).jsonObject

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = BoundedProtocolList.create(values).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}

@Serializable
private data class VerifiedFixture(
    val status: String = "complete",
    val state: String = "verified",
    val receiptIdentity: String = "receipt:fixture",
    val freshReference: String = "exact:v3:unchanged-opaque-bytes",
    val changes: List<DiffFixture> = listOf(DiffFixture("Fixture.kt", "replace-body", "- old\n+ new")),
)

@Serializable private data class DiffFixture(val path: String, val kind: String, val diff: String)
