@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal fun sourceRoot(element: JsonElement): JsonObject {
    val root = sourceObjectAt(element, SourceRequestPath.DOCUMENT)
    if ("symbol" in root) {
        val descriptor = SourceReadSimpleRequest.serializer().descriptor
        sourceFields(
            root,
            SourceRequestPath.DOCUMENT,
            (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet(),
        )
        return root
    }
    sourceFields(
        root,
        SourceRequestPath.DOCUMENT,
        setOf(
            "anchor",
            "region",
            "entities",
            "text",
            "entityLimit",
            "textByteLimit",
            "page",
            "format",
            "execution_budget",
        ),
    )

    return root
}

internal fun decodeSimpleSource(root: JsonObject, json: Json): SourceReadRequest {
    val raw = sourceString(sourceRequired(root, "symbol", SourceRequestPath.SYMBOL), SourceRequestPath.SYMBOL)
    when (val parsed = ExactSymbolSelector.parse(raw)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected ->
            throw SourceRequestSerializationException(
                SourceReadFailureDetail.ReferenceRejected(
                    SourceReferenceRole.SYMBOL,
                    when (parsed.failure) {
                        ExactSymbolSelectorFailure.MALFORMED -> SourceReferenceFailure.MALFORMED
                        ExactSymbolSelectorFailure.WRONG_FAMILY -> SourceReferenceFailure.WRONG_FAMILY
                    },
                )
            )
    }
    return try {
        Json(json) { ignoreUnknownKeys = false }
            .decodeFromJsonElement(SourceReadSimpleRequest.serializer(), root)
            .canonical()
    } catch (_: SerializationException) {
        throw SourceRequestSerializationException(
            SourceReadFailureDetail.RequestRejected(
                SourceRequestField(SourceRequestPath.DOCUMENT),
                SourceRequestRule.INVALID_JSON,
            )
        )
    }
}
