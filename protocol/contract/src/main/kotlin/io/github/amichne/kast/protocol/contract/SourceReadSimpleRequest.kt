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

enum class ExactSymbolSelectorFailure { MALFORMED, WRONG_FAMILY }

/** A syntactically exact selector. The live host still proves ownership and freshness. */
@JvmInline
@Serializable(with = ExactSymbolSelectorSerializer::class)
value class ExactSymbolSelector private constructor(val encoded: String) {
    companion object {
        fun parse(raw: String): Refinement<ExactSymbolSelector, ExactSymbolSelectorFailure> {
            val text = when (val admitted = ProtocolText.parse(raw)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(ExactSymbolSelectorFailure.MALFORMED)
            }
            return when (val admitted = SourceReadAnchorDocument.admit(text)) {
                is Refinement.Refined ->
                    if (admitted.value is SourceReadAnchorDocument.Symbol) Refinement.Refined(ExactSymbolSelector(raw))
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
            is Refinement.Rejected -> throw kotlinx.serialization.SerializationException("Invalid exact symbol selector")
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
    @Serializable @SerialName("window") data class Window(
        val beforeLines: SourceLineCountDocument = sourceLineCount(20),
        val afterLines: SourceLineCountDocument = sourceLineCount(20),
        val maximumBytes: SourceTextByteLimitDocument,
    ) : SimpleSourceText
}

@Serializable
@JsonClassDiscriminator("mode")
sealed interface SimpleSourceEntities {
    @Serializable @SerialName("none") data object None : SimpleSourceEntities
    @Serializable @SerialName("declarations") data class Declarations(val limit: SourceEntityLimitDocument) : SimpleSourceEntities
}

/** Public exact-symbol request; its descriptor generates the short MCP branch. */
@Serializable
data class SourceReadSimpleRequest(
    @ProtocolStringConstraint(pattern = "^exact:(?:v4:[0-9a-f]{64}|v5:[A-Za-z0-9_-]{21}[AQgw]|v[23]:[A-Za-z0-9_-]+:[0-9a-f]{64})$")
    val symbol: ExactSymbolSelector,
    val region: SimpleSourceRegion = SimpleSourceRegion.DECLARATION,
    val text: SimpleSourceText = SimpleSourceText.Complete,
    val entities: SimpleSourceEntities = SimpleSourceEntities.None,
    val format: SourceReadFormatDocument = SourceReadFormatDocument.COMPACT,
) {
    fun canonical(): SourceReadRequest = SourceReadRequest(
        anchor = SourceReadAnchorDocument.Symbol(
            when (val parsed = ProtocolText.parse(symbol.encoded)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> error("ExactSymbolSelector lost its admitted text")
            }
        ),
        region = when (region) {
            SimpleSourceRegion.DECLARATION -> SourceRegionSelectionDocument.Anchor
            SimpleSourceRegion.BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE)
            SimpleSourceRegion.CLASS_BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CLASS)
            SimpleSourceRegion.FILE -> SourceRegionSelectionDocument.File
        },
        entities = when (entities) {
            SimpleSourceEntities.None -> SourceEntitySelectionDocument.None
            is SimpleSourceEntities.Declarations -> SourceEntitySelectionDocument.Matching(
                SourceContainmentDocument.DIRECT,
                listOf(SourceEntityFilterDocument.Declarations(
                    SourceDeclarationKindDocument.entries.toList(),
                    SourceVisibilitySelectionDocument.Any,
                )),
            )
        },
        text = when (text) {
            SimpleSourceText.Complete -> SourceTextRequestDocument.Complete
            SimpleSourceText.None -> SourceTextRequestDocument.None
            is SimpleSourceText.Window -> SourceTextRequestDocument.Window(text.beforeLines, text.afterLines)
        },
        entityLimit = when (entities) {
            SimpleSourceEntities.None -> sourceEntityLimit(250)
            is SimpleSourceEntities.Declarations -> entities.limit
        },
        textByteLimit = when (text) {
            is SimpleSourceText.Window -> text.maximumBytes
            else -> sourceTextByteLimit(65_536)
        },
        page = SourceReadPageDocument.First,
        format = format,
    )
}

private fun sourceEntityLimit(raw: Int): SourceEntityLimitDocument =
    (SourceEntityLimitDocument.parse(raw) as Refinement.Refined).value

private fun sourceTextByteLimit(raw: Long): SourceTextByteLimitDocument =
    (SourceTextByteLimitDocument.parse(raw) as Refinement.Refined).value

private fun sourceLineCount(raw: Int): SourceLineCountDocument =
    (SourceLineCountDocument.parse(raw) as Refinement.Refined).value
