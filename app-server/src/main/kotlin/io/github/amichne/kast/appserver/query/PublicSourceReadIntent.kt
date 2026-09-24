@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceLineCountDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocumentFailure
import io.github.amichne.kast.protocol.contract.SourceReadCause
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReferenceFailure
import io.github.amichne.kast.protocol.contract.SourceReferenceRole
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceRequestField
import io.github.amichne.kast.protocol.contract.SourceRequestIngress
import io.github.amichne.kast.protocol.contract.SourceRequestPath
import io.github.amichne.kast.protocol.contract.SourceRequestRule
import io.github.amichne.kast.protocol.contract.SourceRequestSerializationException
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** Public source syntax; canonical source identity and execution remain with source.read. */
@Serializable
data class PublicSourceReadIntent(
    val anchor: PublicSourceAnchor,
    val region: PublicSourceRegion = PublicSourceRegion.DECLARATION,
    val text: PublicSourceText = PublicSourceText.Complete(),
    val entities: PublicSourceEntities = PublicSourceEntities.None,
    val page: SourceReadPageDocument = SourceReadPageDocument.First,
    @SerialName("execution_budget") val executionBudget: ExecutionBudgetDocument? = null,
)

@Serializable data class PublicSourceAnchor(val symbolRef: ProtocolText)

@Serializable
enum class PublicSourceRegion {
    @SerialName("declaration") DECLARATION,
    @SerialName("file") FILE,
    @SerialName("class_body") CLASS_BODY,
    @SerialName("callable_body") CALLABLE_BODY,
}

@Serializable
@JsonClassDiscriminator("mode")
sealed interface PublicSourceText {
    @Serializable @SerialName("complete") data class Complete(val maxBytes: Long? = null) : PublicSourceText

    @Serializable @SerialName("none") data object None : PublicSourceText

    @Serializable
    @SerialName("window")
    data class Window(val beforeLines: Int, val afterLines: Int, val maxBytes: Long? = null) : PublicSourceText
}

@Serializable
@JsonClassDiscriminator("mode")
sealed interface PublicSourceEntities {
    @Serializable @SerialName("none") data object None : PublicSourceEntities

    @Serializable
    @SerialName("matching")
    data class Matching(
        val filters: List<SourceEntityFilterDocument>,
        val limit: Int? = null,
        val containment: SourceContainmentDocument = SourceContainmentDocument.DIRECT,
    ) : PublicSourceEntities
}

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
internal fun PublicSourceReadIntent.lower(): Refinement<SourceReadRequest, SourceReadCause> {
    val admittedAnchor =
        when (val admitted = SourceReadAnchorDocument.admit(anchor.symbolRef)) {
            is Refinement.Refined ->
                admitted.value as? SourceReadAnchorDocument.Symbol
                    ?: return Refinement.Rejected(
                        SourceReadFailureDetail.ReferenceRejected(
                            SourceReferenceRole.SYMBOL,
                            SourceReferenceFailure.WRONG_FAMILY,
                        )
                    )
            is Refinement.Rejected ->
                return Refinement.Rejected(
                    SourceReadFailureDetail.ReferenceRejected(
                        SourceReferenceRole.SYMBOL,
                        when (admitted.failure) {
                            SourceReadAnchorDocumentFailure.UNKNOWN_TOKEN_FAMILY,
                            SourceReadAnchorDocumentFailure.INVALID_TOKEN_STRUCTURE -> SourceReferenceFailure.MALFORMED
                            SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING ->
                                SourceReferenceFailure.INVALID_PAYLOAD_ENCODING
                            SourceReadAnchorDocumentFailure.PAYLOAD_DIGEST_MISMATCH ->
                                SourceReferenceFailure.PAYLOAD_DIGEST_MISMATCH
                        },
                    )
                )
        }
    val selectedRegion =
        when (region) {
            PublicSourceRegion.DECLARATION ->
                SourceRegionSelectionDocument.Enclosing(SourceEnclosingRegionKindDocument.DECLARATION)
            PublicSourceRegion.FILE -> SourceRegionSelectionDocument.File
            PublicSourceRegion.CLASS_BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CLASS)
            PublicSourceRegion.CALLABLE_BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE)
        }
    val selectedText =
        when (val request = text) {
            is PublicSourceText.Complete -> SourceTextRequestDocument.Complete
            PublicSourceText.None -> SourceTextRequestDocument.None
            is PublicSourceText.Window -> {
                val before =
                    when (val parsed = SourceLineCountDocument.parse(request.beforeLines)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected ->
                            return fieldRejected(SourceRequestPath.BEFORE_LINES, SourceRequestRule.LINE_COUNT)
                    }
                val after =
                    when (val parsed = SourceLineCountDocument.parse(request.afterLines)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected ->
                            return fieldRejected(SourceRequestPath.AFTER_LINES, SourceRequestRule.LINE_COUNT)
                    }
                SourceTextRequestDocument.Window(before, after)
            }
        }
    val selectedEntities =
        when (val request = entities) {
            PublicSourceEntities.None -> SourceEntitySelectionDocument.None
            is PublicSourceEntities.Matching -> {
                if (request.filters.isEmpty() || request.filters.size > MAX_PUBLIC_SOURCE_FILTERS)
                    return fieldRejected(SourceRequestPath.FILTERS, SourceRequestRule.FILTER_COUNT)
                SourceEntitySelectionDocument.Matching(request.containment, request.filters)
            }
        }
    val limit =
        when (val request = entities) {
            PublicSourceEntities.None -> null
            is PublicSourceEntities.Matching -> request.limit
        }?.let { raw ->
            when (val parsed = SourceEntityLimitDocument.parse(raw)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return fieldRejected(SourceRequestPath.ENTITY_LIMIT, SourceRequestRule.ENTITY_COUNT)
            }
        }
    val bytes =
        when (val request = text) {
            is PublicSourceText.Complete -> request.maxBytes
            is PublicSourceText.Window -> request.maxBytes
            PublicSourceText.None -> null
        }?.let { raw ->
            when (val parsed = SourceTextByteLimitDocument.parse(raw)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return fieldRejected(SourceRequestPath.TEXT_BYTE_LIMIT, SourceRequestRule.BYTE_COUNT)
            }
        }
    val base = SourceReadRequest(admittedAnchor, selectedRegion, selectedEntities, selectedText)
    val canonical =
        base.copy(
            entityLimit = limit ?: base.entityLimit,
            textByteLimit = bytes ?: base.textByteLimit,
            page = page,
            executionBudget = executionBudget,
            format = SourceReadFormatDocument.COMPACT,
        )
    val encoded =
        try {
            sourceIntentJson.encodeToJsonElement(SourceReadRequest.serializer(), canonical)
        } catch (_: SerializationException) {
            return fieldRejected(SourceRequestPath.FILTERS, SourceRequestRule.NONEMPTY_UNIQUE_VALUES)
        }
    return SourceRequestIngress.decode(encoded, sourceIntentJson)
}

private const val MAX_PUBLIC_SOURCE_FILTERS = 4
private val sourceIntentJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
}

private fun fieldRejected(path: SourceRequestPath, rule: SourceRequestRule): Refinement.Rejected<SourceReadCause> =
    Refinement.Rejected(SourceReadFailureDetail.RequestRejected(SourceRequestField(path), rule))

/** Both public forms refine to one canonical request before the direct CLI or MCP invokes IDEA. */
object PublicSourceReadRequestSerializer : KSerializer<SourceReadRequest> {
    override val descriptor = SourceReadRequest.serializer().descriptor

    override fun deserialize(decoder: Decoder): SourceReadRequest {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Source requests require JSON")
        val element = input.decodeJsonElement()
        val anchor = (element as? JsonObject)?.get("anchor") as? JsonObject
        val admitted =
            if (anchor != null && "symbolRef" in anchor) {
                val intent = input.json.decodeFromJsonElement(PublicSourceReadIntent.serializer(), element)
                intent.lower()
            } else {
                SourceRequestIngress.decode(element, input.json)
            }
        return when (admitted) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> throw SourceRequestSerializationException(admitted.failure)
        }
    }

    override fun serialize(encoder: Encoder, value: SourceReadRequest) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Source requests require JSON")
        output.encodeJsonElement(output.json.encodeToJsonElement(SourceReadRequest.serializer(), value))
    }
}
