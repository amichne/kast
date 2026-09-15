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
