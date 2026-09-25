@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

private const val DEFAULT_WINDOW_LINES = 20
private const val EXACT_SELECTOR_PATTERN =
    "^exact:(?:v4:[0-9a-f]{64}|v5:[A-Za-z0-9_-]{21}[AQgw])$"

enum class ExactSymbolSelectorFailure {
    MALFORMED,
    WRONG_FAMILY,
}

/** A syntactically exact selector. The live host still proves ownership and freshness. */
@JvmInline
@Serializable(with = ExactSymbolSelectorSerializer::class)
value class ExactSymbolSelector private constructor(val encoded: String) {
    companion object {
        fun parse(raw: String): Refinement<ExactSymbolSelector, ExactSymbolSelectorFailure> {
            val text =
                when (val admitted = ProtocolText.parse(raw)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return Refinement.Rejected(ExactSymbolSelectorFailure.MALFORMED)
                }
            return when (val admitted = HostedSymbolHandle.parse(text)) {
                is Refinement.Refined ->
                    if (admitted.value.family == HostedSymbolHandleFamily.EXACT) Refinement.Refined(ExactSymbolSelector(raw))
                    else Refinement.Rejected(ExactSymbolSelectorFailure.WRONG_FAMILY)
                is Refinement.Rejected -> Refinement.Rejected(ExactSymbolSelectorFailure.MALFORMED)
            }
        }
    }
}

internal object ExactSymbolSelectorSerializer : KSerializer<ExactSymbolSelector> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ExactSymbolSelector", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExactSymbolSelector) = encoder.encodeString(value.encoded)

    override fun deserialize(decoder: Decoder): ExactSymbolSelector =
        when (val parsed = ExactSymbolSelector.parse(decoder.decodeString())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected ->
                throw kotlinx.serialization.SerializationException("Invalid exact symbol selector")
        }
}

@Serializable
enum class SimpleSourceRegion {
    @SerialName("declaration") DECLARATION,
    @SerialName("body") BODY,
    @SerialName("class-body") CLASS_BODY,
    @SerialName("file") FILE,
}

@Serializable
@JsonClassDiscriminator("mode")
sealed interface SimpleSourceText {
    @Serializable @SerialName("complete") data object Complete : SimpleSourceText

    @Serializable @SerialName("none") data object None : SimpleSourceText

    @Serializable
    @SerialName("window")
    data class Window(
        val beforeLines: SourceLineCountDocument = sourceLineCount(DEFAULT_WINDOW_LINES),
        val afterLines: SourceLineCountDocument = sourceLineCount(DEFAULT_WINDOW_LINES),
        val maximumBytes: SourceTextByteLimitDocument,
    ) : SimpleSourceText
}

@Serializable
@JsonClassDiscriminator("mode")
sealed interface SimpleSourceEntities {
    @Serializable @SerialName("none") data object None : SimpleSourceEntities

    @Serializable
    @SerialName("declarations")
    data class Declarations(val limit: SourceEntityLimitDocument) : SimpleSourceEntities
}

/** Public exact-symbol request; its descriptor generates the short MCP branch. */
@Serializable
data class SourceReadSimpleRequest(
    @ProtocolStringConstraint(pattern = EXACT_SELECTOR_PATTERN) val symbol: ExactSymbolSelector,
    val region: SimpleSourceRegion = SimpleSourceRegion.DECLARATION,
    val text: SimpleSourceText = SimpleSourceText.Complete,
    val entities: SimpleSourceEntities = SimpleSourceEntities.None,
    val format: SourceReadFormatDocument = SourceReadFormatDocument.COMPACT,
) {
    fun canonical(): SourceReadRequest {
        val base =
            SourceReadRequest(
                anchor =
                    SourceReadAnchorDocument.Symbol(
                        when (val parsed = ProtocolText.parse(symbol.encoded)) {
                            is Refinement.Refined -> parsed.value
                            is Refinement.Rejected -> error("ExactSymbolSelector lost its admitted text")
                        }
                    ),
                region = region.canonicalSelection(),
                entities = entities.canonicalSelection(),
                text = text.canonicalRequest(),
                page = SourceReadPageDocument.First,
                format = format,
            )
        return base.copy(
            entityLimit =
                when (entities) {
                    SimpleSourceEntities.None -> base.entityLimit
                    is SimpleSourceEntities.Declarations -> entities.limit
                },
            textByteLimit =
                when (text) {
                    is SimpleSourceText.Window -> text.maximumBytes
                    else -> base.textByteLimit
                },
        )
    }
}

private fun sourceLineCount(raw: Int): SourceLineCountDocument =
    (SourceLineCountDocument.parse(raw) as Refinement.Refined).value

private fun SimpleSourceRegion.canonicalSelection(): SourceRegionSelectionDocument =
    when (this) {
        SimpleSourceRegion.DECLARATION -> SourceRegionSelectionDocument.Anchor
        SimpleSourceRegion.BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE)
        SimpleSourceRegion.CLASS_BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CLASS)
        SimpleSourceRegion.FILE -> SourceRegionSelectionDocument.File
    }

private fun SimpleSourceEntities.canonicalSelection(): SourceEntitySelectionDocument =
    when (this) {
        SimpleSourceEntities.None -> SourceEntitySelectionDocument.None
        is SimpleSourceEntities.Declarations ->
            SourceEntitySelectionDocument.Matching(
                SourceContainmentDocument.DIRECT,
                listOf(
                    SourceEntityFilterDocument.Declarations(
                        SourceDeclarationKindDocument.entries.toList(),
                        SourceVisibilitySelectionDocument.Any,
                    )
                ),
            )
    }

private fun SimpleSourceText.canonicalRequest(): SourceTextRequestDocument =
    when (this) {
        SimpleSourceText.Complete -> SourceTextRequestDocument.Complete
        SimpleSourceText.None -> SourceTextRequestDocument.None
        is SimpleSourceText.Window -> SourceTextRequestDocument.Window(beforeLines, afterLines)
    }
