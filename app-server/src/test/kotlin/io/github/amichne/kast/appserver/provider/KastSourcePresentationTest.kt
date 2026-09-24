package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.acceptance.hostedchange.NativeSourcePlacement
import io.github.amichne.kast.appserver.acceptance.hostedchange.nativePresentationEvidence
import io.github.amichne.kast.appserver.core.ToolPresentation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        assertEquals(
            "OrderService — class\nsrc/OrderService.kt @ offset 28\ncom.example.OrderService\n1 result; requested scope exhausted",
            presentation.content.first().text,
        )
        assertEquals(document, json.parseToJsonElement(presentation.content.last().text))
    }

    @Test
    fun `diagnostic summary names IDE scope without implying a build`() {
        val json = Json { encodeDefaults = true }
        val document =
            json
                .encodeToJsonElement(
                    DiagnosticPresentationEnvelope.serializer(),
                    DiagnosticPresentationEnvelope(DiagnosticFixture()),
                )
                .jsonObject
        val presentation = presentKastSourceOrOutcome(document, true)
        assertEquals(
            "No diagnostics in 1 of 1 analyzed files. IDE file diagnostics; project build not run.",
            presentation.content.first().text,
        )
        assertEquals(document, json.parseToJsonElement(presentation.content.last().text))
    }

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
        assertEquals(NativeSourcePlacement.VERIFIED, nativePresentationEvidence(presentation).sourcePlacement)
        assertEquals(
            NativeSourcePlacement.MISMATCH,
            nativePresentationEvidence(ToolPresentation.outcome(document, true)).sourcePlacement,
        )
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

@Serializable
private data class DiagnosticPresentationEnvelope(val document: DiagnosticFixture, val status: String = "completed")

@Serializable
private data class DiagnosticFixture(
    val operation: String = "diagnostic.check",
    val status: String = "complete",
    val diagnostics: List<String> = emptyList(),
    val progress: DiagnosticProgressFixture = DiagnosticProgressFixture(),
)

@Serializable
private data class DiagnosticProgressFixture(
    val analyzedFiles: List<String> = listOf("src/A.kt"),
    val inventory: DiagnosticInventoryFixture = DiagnosticInventoryFixture(),
)

@Serializable private data class DiagnosticInventoryFixture(val type: String = "exhausted", val totalFiles: Int = 1)

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
