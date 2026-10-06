package io.github.amichne.kast.appserver.provider

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
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalDiagnosticCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastSourcePresentationTest {
    @Test
    fun `query summary distinguishes exhausted absence from partial absence`() {
        val json = Json { encodeDefaults = true }
        fun present(status: String) =
            presentKastSourceOrOutcome(
                    json
                        .encodeToJsonElement(
                            SearchPresentationEnvelope.serializer(),
                            SearchPresentationEnvelope(SearchFixture(status)),
                        )
                        .jsonObject,
                    true,
                )
                .content
                .first()
                .text

        assertEquals("0 results; requested scope exhausted", present("complete"))
        assertEquals("0 results; partial; absence unverified", present("qualified"))
    }

    @Test
    fun `query summary keeps opaque reference in the machine document`() {
        val json = Json { encodeDefaults = true }
        val document =
            json
                .encodeToJsonElement(
                    SearchPresentationEnvelope.serializer(),
                    SearchPresentationEnvelope(
                        SearchFixture(
                            "complete",
                            items = listOf(SearchItemFixture("candidate:opaque", "OrderService", "class")),
                        )
                    ),
                )
                .jsonObject
        val presentation = presentKastSourceOrOutcome(document, true)
        val summary =
            "OrderService — class\nsrc/OrderService.kt @ offset 28\n" +
                "com.example.OrderService\n1 result; requested scope exhausted"
        assertEquals(summary, presentation.content.first().text)
        assertEquals(document, json.parseToJsonElement(presentation.content.last().text))
    }

    @Test
    fun `compact and verbose diagnostic summaries retain exact coverage without implying a build`() {
        assertDiagnosticSummary(
            completeDiagnostics(diagnosticProgress()),
            "No diagnostics in 1 of 1 analyzed files. IDE file diagnostics; project build not run.",
        )
    }

    @Test
    fun `qualified diagnostics preserve skipped files and incomplete coverage`() {
        val progress = diagnosticProgress(discovered = 2)
        val qualification =
            DiagnosticCheckQualification.create(
                knownDiagnosticCount = refined(DiagnosticKnownCountDocument.parse(0)),
                resultLimitReached = false,
                analyzedFiles = progress.analyzedFiles,
                limitations =
                    listOf(
                        DiagnosticLimitationDocument(
                            refined(ProtocolText.parse("src/B.kt")),
                            DiagnosticLimitationReasonDocument.ANALYSIS_UNAVAILABLE,
                        )
                    ),
            )
        val result =
            CanonicalDiagnosticCliDocuments.project(
                OperationOutcome.Qualified(diagnosticEvidence(progress), refined(qualification))
            )
        assertDiagnosticSummary(
            result,
            "0 diagnostics returned; 1 of 2 analyzed files; scan incomplete. " +
                "IDE file diagnostics; project build not run.",
        )
    }

    @Test
    fun `diagnostic enumeration does not invent a discovered file count`() {
        val progress =
            diagnosticProgress()
                .copy(
                    stage = DiagnosticProgressStage.ENUMERATION,
                    inventory = DiagnosticInventoryDocument.Enumerating,
                    analyzedFiles = emptyList(),
                    stop = DiagnosticProgressStop.ENUMERATION_WORK_LIMIT,
                )
        val qualification =
            DiagnosticCheckQualification.create(
                knownDiagnosticCount = refined(DiagnosticKnownCountDocument.parse(0)),
                resultLimitReached = true,
                analyzedFiles = emptyList(),
                limitations = emptyList(),
            )
        val result =
            CanonicalDiagnosticCliDocuments.project(
                OperationOutcome.Qualified(diagnosticEvidence(progress), refined(qualification))
            )
        assertDiagnosticSummary(
            result,
            "0 diagnostics returned; 0 analyzed files; discovery incomplete; scan incomplete. " +
                "IDE file diagnostics; project build not run.",
        )
    }

    @Test
    fun `diagnostic result without coverage does not claim a clean scope`() {
        assertDiagnosticSummary(
            completeDiagnostics(null),
            "0 diagnostics returned; coverage unavailable. IDE file diagnostics; project build not run.",
        )
    }

    private fun assertDiagnosticSummary(result: ProjectedOperationOutcome, expected: String) {
        for (detail in ToolOutputDetail.entries) {
            val document = diagnosticEnvelope(result, detail)
            val presentation = presentKastSourceOrOutcome(document, true)
            assertEquals(expected, presentation.content.first().text)
            assertEquals(document, Json.parseToJsonElement(presentation.content.last().text))
        }
    }

    private fun completeDiagnostics(progress: DiagnosticProgressDocument?) =
        CanonicalDiagnosticCliDocuments.project(OperationOutcome.Complete(diagnosticEvidence(progress)))

    private fun diagnosticEvidence(progress: DiagnosticProgressDocument?) =
        EvidenceEnvelope(
            CanonicalOperation.DIAGNOSTIC_CHECK.id,
            refined(EvidenceGeneration.parse(1)),
            DiagnosticCheckResult(refined(BoundedProtocolList.create(emptyList())), progress),
        )

    private fun diagnosticProgress(discovered: Int = 1): DiagnosticProgressDocument {
        val path = refined(ProtocolText.parse("src/A.kt"))
        return DiagnosticProgressDocument(
            stage = DiagnosticProgressStage.FINISHED,
            inventory = DiagnosticInventoryDocument.Exhausted(refined(ProtocolCount.parse(discovered))),
            analyzedFiles = listOf(path),
            stop = DiagnosticProgressStop.FINISHED,
            knownDiagnosticCount = refined(DiagnosticKnownCountDocument.parse(0)),
            requestedPath = path,
        )
    }

    private fun diagnosticEnvelope(result: ProjectedOperationOutcome, detail: ToolOutputDetail): JsonObject {
        val json = Json { encodeDefaults = true }
        val projected =
            when (result) {
                is ProjectedOperationOutcome.Complete -> result.document
                is ProjectedOperationOutcome.Qualified -> result.document
                is ProjectedOperationOutcome.Rejected -> result.document
            }
        return json
            .encodeToJsonElement(
                DiagnosticPresentationEnvelope.serializer(),
                DiagnosticPresentationEnvelope(Json.parseToJsonElement(projected.present(detail).value).jsonObject),
            )
            .jsonObject
    }

    private fun <T> refined(result: Refinement<T, *>): T = (result as Refinement.Refined).value

    @Test
    fun `source presentation emits unchanged non ASCII source before structured detail`() {
        val source = "fun café() = \"🚀\"\n"
        val json = Json { encodeDefaults = true }
        val selection = PresentationSelection(0, PresentationRange(0, source.length))
        val fixture =
            PresentationFixture(
                content =
                    listOf(
                        PresentationSection.Source(PresentationText(source, selection)),
                        PresentationSection.Structure(
                            PresentationSnapshot(length = source.length),
                            PresentationRegion(selection),
                        ),
                    )
            )
        val document =
            json.encodeToJsonElement(PresentationEnvelope.serializer(), PresentationEnvelope(fixture)).jsonObject
        val presentation = presentKastSourceOrOutcome(document, true)
        assertEquals(source, presentation.content.first().text)
        assertEquals(document, json.parseToJsonElement(presentation.content.last().text))
        assertEquals(2, presentation.content.size)
    }
}

@Serializable
private data class SearchPresentationEnvelope(val document: SearchFixture, val status: String = "completed")

@Serializable
private data class SearchFixture(
    val status: String,
    val operation: String = "query.run",
    val items: List<SearchItemFixture> = emptyList(),
    val failures: List<String> = emptyList(),
)

@Serializable
private data class SearchItemFixture(
    val ref: String,
    val name: String,
    val kind: String,
    val location: SearchLocationFixture = SearchLocationFixture(),
    val signature: SearchSignatureFixture = SearchSignatureFixture(),
)

@Serializable private data class SearchLocationFixture(val file: String = "src/OrderService.kt", val offset: Int = 28)

@Serializable private data class SearchSignatureFixture(val qualifiedIdentity: String = "com.example.OrderService")

/** The canonical projector owns this operation-specific document shape. */
@Serializable
private data class DiagnosticPresentationEnvelope(val document: JsonObject, val status: String = "completed")

@Serializable
private data class PresentationFixture(
    val operation: String = "source.read",
    val status: String = "complete",
    val format: String = "compact",
    val content: List<PresentationSection>,
)

@Serializable
private sealed interface PresentationSection {
    @Serializable @SerialName("source") data class Source(val text: PresentationText) : PresentationSection

    @Serializable
    @SerialName("structure")
    data class Structure(
        val snapshot: PresentationSnapshot,
        val region: PresentationRegion,
        val selections: List<PresentationEntry> = listOf(PresentationEntry("source-selector-v1|fixture")),
        val entities: List<String> = emptyList(),
    ) : PresentationSection
}

@Serializable
private data class PresentationText(
    val text: String,
    val selection: PresentationSelection,
    val lines: PresentationLines = PresentationLines(),
    val type: String = "returned",
)

@Serializable
private data class PresentationSnapshot(
    val canonicalRoot: String = "/workspace",
    val generation: Long = 1,
    val sourceState: String = "state",
    val file: String = "Subject.kt",
    val textIdentity: String = "identity",
    val coordinateUnit: String = "utf16-code-unit",
    val length: Int,
)

@Serializable private data class PresentationEntry(val selector: String)

@Serializable private data class PresentationRegion(val selection: PresentationSelection, val kind: String = "file")

@Serializable private data class PresentationSelection(val id: Int, val range: PresentationRange)

@Serializable private data class PresentationRange(val startInclusive: Int, val endExclusive: Int)

@Serializable private data class PresentationLines(val startInclusive: Long = 1, val endInclusive: Long = 1)

@Serializable
private data class PresentationEnvelope(val document: PresentationFixture, val status: String = "completed")
