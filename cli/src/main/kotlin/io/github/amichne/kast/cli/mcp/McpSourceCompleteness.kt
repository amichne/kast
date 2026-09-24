package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.protocol.wire.CompactSourceTextDocument
import io.github.amichne.kast.protocol.wire.presentation.CompactSourceContentDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** A source format must be decoded before its completeness can be asserted. */
internal data class McpSourceCompleteness(val region: JsonElement, val textWithheld: Boolean)

internal fun sourceCompleteness(canonical: JsonElement): McpSourceCompleteness? {
    val compact =
        try {
            sourceSummaryJson.decodeFromJsonElement<McpCompactSourceSummary>(canonical)
        } catch (_: SerializationException) {
            null
        }
    if (compact != null) {
        val structure = compact.content.filterIsInstance<CompactSourceContentDocument.Structure>().singleOrNull()
        val source = compact.content.filterIsInstance<CompactSourceContentDocument.Source>().singleOrNull()
        if (structure == null || source == null) return null
        return McpSourceCompleteness(
            sourceSummaryJson.encodeToJsonElement(structure.region),
            source.text is CompactSourceTextDocument.Withheld,
        )
    }
    val expanded =
        try {
            sourceSummaryJson.decodeFromJsonElement<McpExpandedSourceSummary>(canonical)
        } catch (_: SerializationException) {
            return null
        }
    return McpSourceCompleteness(
        sourceSummaryJson.encodeToJsonElement(expanded.region),
        expanded.text is McpExpandedSourceText.Withheld,
    )
}

@Serializable private data class McpCompactSourceSummary(val content: List<CompactSourceContentDocument>)

@Serializable
private data class McpExpandedSourceSummary(
    val region: McpExpandedSourceRegion,
    val text: McpExpandedSourceText,
)

@Serializable
private data class McpExpandedSourceRegion(
    val kind: McpExpandedSourceRegionKind,
    val selection: McpExpandedSourceSelection,
)

@Serializable
private enum class McpExpandedSourceRegionKind {
    @SerialName("anchor") ANCHOR,
    @SerialName("declaration") DECLARATION,
    @SerialName("callable-body") CALLABLE_BODY,
    @SerialName("class-body") CLASS_BODY,
    @SerialName("file") FILE,
    @SerialName("window") WINDOW,
}

@Serializable private data class McpExpandedSourceSelection(val selector: String, val range: McpExpandedSourceRange)

@Serializable private data class McpExpandedSourceRange(val startInclusive: Int, val endExclusive: Int)

@Serializable
private sealed interface McpExpandedSourceText {
    @Serializable @SerialName("not-requested") data object NotRequested : McpExpandedSourceText

    @Serializable
    @SerialName("returned")
    data class Returned(
        val selection: McpExpandedSourceSelection,
        val text: String,
        val lines: McpExpandedSourceLines,
    ) : McpExpandedSourceText

    @Serializable @SerialName("withheld") data class Withheld(val reason: String) : McpExpandedSourceText
}

@Serializable private data class McpExpandedSourceLines(val startInclusive: Long, val endInclusive: Long)

private val sourceSummaryJson = Json { ignoreUnknownKeys = true }
