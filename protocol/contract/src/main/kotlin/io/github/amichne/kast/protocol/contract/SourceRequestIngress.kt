@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parser exceptions carry only finite authored evidence, never parser messages or request bytes. */
class SourceRequestSerializationException(val failure: SourceReadCause) :
    SerializationException("Source request rejected")

/** Physical ingress for SOURCE only. The returned request retains the generated DTO's refined values. */
object SourceRequestIngress {
    fun decode(element: JsonElement, json: Json): Refinement<SourceReadRequest, SourceReadCause> =
        decodeWithPolicy(element, json, SourceEntityLimitPolicy.CANONICAL)

    fun decodePublic(element: JsonElement, json: Json): Refinement<SourceReadRequest, SourceReadCause> =
        decodeWithPolicy(element, json, SourceEntityLimitPolicy.PUBLIC)

    private fun decodeWithPolicy(
        element: JsonElement,
        json: Json,
        policy: SourceEntityLimitPolicy,
    ): Refinement<SourceReadRequest, SourceReadCause> =
        try {
            val root = sourceRoot(element)
            if ("symbol" in root) return Refinement.Refined(decodeSimpleSource(root, json))
            validateSourceAnchor(root)
            validateSourceRegion(root)
            validateSourceEntities(root)
            if (
                policy == SourceEntityLimitPolicy.PUBLIC &&
                    "entityLimit" in root &&
                    ((root["entities"] as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "none"
            ) {
                rejectSourceField(SourceRequestPath.ENTITY_LIMIT, SourceRequestRule.ENTITY_LIMIT_NOT_APPLICABLE)
            }
            validateSourceText(root)
            validateSourceLimits(root)
            if ("page" in root) validateSourcePage(root)
            validateSourceExecutionBudget(root)
            if ("format" in root)
                sourceChoice(
                    root,
                    "format",
                    SourceRequestPath.FORMAT,
                    SourceRequestRule.FORMAT,
                    listOf("compact", "expanded"),
                )
            val result =
                try {
                    json.decodeFromJsonElement(SourceReadRequest.generatedSerializer(), element)
                } catch (_: SerializationException) {
                    throw SourceRequestSerializationException(
                        SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
                    )
                }
            Refinement.Refined(result)
        } catch (failure: SourceRequestSerializationException) {
            Refinement.Rejected(failure.failure)
        }
}

private fun validateSourceLimits(root: JsonObject) {
    if ("entityLimit" in root)
        sourceNumber(
            root,
            "entityLimit",
            SourceRequestPath.ENTITY_LIMIT,
            1,
            MAX_SOURCE_READ_ENTITY_LIMIT.toLong(),
            SourceRequestRule.ENTITY_COUNT,
        )
    if ("textByteLimit" in root)
        sourceNumber(
            root,
            "textByteLimit",
            SourceRequestPath.TEXT_BYTE_LIMIT,
            1,
            Long.MAX_VALUE,
            SourceRequestRule.BYTE_COUNT,
        )
}

private enum class SourceEntityLimitPolicy {
    CANONICAL,
    PUBLIC,
}

private fun validateSourceAnchor(root: JsonObject) {
    val anchor = sourceObjectAt(sourceRequired(root, "anchor", SourceRequestPath.ANCHOR), SourceRequestPath.ANCHOR)
    sourceFields(anchor, SourceRequestPath.ANCHOR, setOf("type", "selector"))
    val role =
        sourceChoice(
            anchor,
            "type",
            SourceRequestPath.ANCHOR_TYPE,
            SourceRequestRule.ANCHOR_TYPE,
            listOf("candidate", "symbol", "source"),
        )
    val selector =
        sourceString(
            sourceRequired(anchor, "selector", SourceRequestPath.ANCHOR_SELECTOR),
            SourceRequestPath.ANCHOR_SELECTOR,
        )
    val text =
        when (val value = ProtocolText.parse(selector)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected ->
                throw SourceRequestSerializationException(
                    SourceReadFailureDetail.ReferenceRejected(role.role(), SourceReferenceFailure.MALFORMED)
                )
        }
    validateAnchorFamily(text, role)
}

private fun validateAnchorFamily(text: ProtocolText, role: String) {
    when (val family = SourceReadAnchorDocument.admit(text)) {
        is Refinement.Rejected ->
            throw SourceRequestSerializationException(
                SourceReadFailureDetail.ReferenceRejected(
                    role.role(),
                    when (family.failure) {
                        SourceReadAnchorDocumentFailure.UNKNOWN_TOKEN_FAMILY,
                        SourceReadAnchorDocumentFailure.INVALID_TOKEN_STRUCTURE -> SourceReferenceFailure.MALFORMED
                        SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING ->
                            SourceReferenceFailure.INVALID_PAYLOAD_ENCODING
                        SourceReadAnchorDocumentFailure.PAYLOAD_DIGEST_MISMATCH ->
                            SourceReferenceFailure.PAYLOAD_DIGEST_MISMATCH
                    },
                )
            )
        is Refinement.Refined -> {
            val actual =
                when (family.value) {
                    is SourceReadAnchorDocument.Candidate -> "candidate"
                    is SourceReadAnchorDocument.Symbol -> "symbol"
                    is SourceReadAnchorDocument.Source -> "source"
                }
            if (role != actual)
                throw SourceRequestSerializationException(
                    SourceReadFailureDetail.ReferenceRejected(role.role(), SourceReferenceFailure.WRONG_FAMILY)
                )
        }
    }
}

private fun validateSourceRegion(root: JsonObject) {
    val region = sourceObjectAt(sourceRequired(root, "region", SourceRequestPath.REGION), SourceRequestPath.REGION)
    when (
        sourceChoice(
            region,
            "type",
            SourceRequestPath.REGION_TYPE,
            SourceRequestRule.REGION_TYPE,
            listOf("anchor", "body", "file", "enclosing"),
        )
    ) {
        "body" -> {
            sourceFields(region, SourceRequestPath.REGION, setOf("type", "kind"))
            sourceChoice(
                region,
                "kind",
                SourceRequestPath.REGION_KIND,
                SourceRequestRule.BODY_KIND,
                listOf("callable", "class"),
            )
        }
        "enclosing" -> {
            sourceFields(region, SourceRequestPath.REGION, setOf("type", "kind"))
            sourceChoice(
                region,
                "kind",
                SourceRequestPath.REGION_KIND,
                SourceRequestRule.ENCLOSING_KIND,
                listOf("declaration", "callable-body", "class-body"),
            )
        }
        else -> sourceFields(region, SourceRequestPath.REGION, setOf("type"))
    }
}

private fun validateSourceEntities(root: JsonObject) {
    val entities =
        sourceObjectAt(sourceRequired(root, "entities", SourceRequestPath.ENTITIES), SourceRequestPath.ENTITIES)
    when (
        sourceChoice(
            entities,
            "type",
            SourceRequestPath.ENTITIES_TYPE,
            SourceRequestRule.ENTITY_SELECTION_TYPE,
            listOf("none", "matching"),
        )
    ) {
        "none" -> sourceFields(entities, SourceRequestPath.ENTITIES, setOf("type"))
        else -> {
            sourceFields(entities, SourceRequestPath.ENTITIES, setOf("type", "containment", "filters"))
            sourceChoice(
                entities,
                "containment",
                SourceRequestPath.CONTAINMENT,
                SourceRequestRule.CONTAINMENT,
                listOf("direct", "descendants"),
            )
            validateSourceFilters(entities)
        }
    }
}

private fun validateSourceFilters(entities: JsonObject) {
    val filters = sourceArray(sourceRequired(entities, "filters", SourceRequestPath.FILTERS), SourceRequestPath.FILTERS)
    if (filters.size !in 1..MAX_SOURCE_FILTERS)
        rejectSourceField(SourceRequestPath.FILTERS, SourceRequestRule.FILTER_COUNT)
    val families = mutableSetOf<String>()

    filters.forEachIndexed { index, value ->
        val filter = sourceObjectAt(value, SourceRequestPath.FILTERS, index)
        val family =
            sourceChoice(
                filter,
                "type",
                SourceRequestPath.FILTER_TYPE,
                SourceRequestRule.FILTER_TYPE,
                listOf("declaration", "parameters", "calls", "references"),
                index,
            )
        if (!families.add(family))
            rejectSourceField(SourceRequestPath.FILTER_TYPE, SourceRequestRule.UNIQUE_FILTER_FAMILIES, index)
        if (family == "declaration") validateDeclarationFilter(filter, index)
        else sourceFields(filter, SourceRequestPath.FILTERS, setOf("type"), index)
    }
}

private fun validateDeclarationFilter(filter: JsonObject, index: Int) {
    sourceFields(filter, SourceRequestPath.FILTERS, setOf("type", "kinds", "visibility"), index)
    sourceChoices(
        filter,
        "kinds",
        SourceRequestPath.DECLARATION_KINDS,
        SourceRequestRule.DECLARATION_KIND,
        listOf("classlike", "constructor", "function", "property", "type-alias"),
        index,
    )
    validateSourceVisibility(filter, index)
}

private fun validateSourceVisibility(filter: JsonObject, index: Int) {
    val visibility =
        sourceObjectAt(
            sourceRequired(filter, "visibility", SourceRequestPath.VISIBILITY, index),
            SourceRequestPath.VISIBILITY,
            index,
        )
    when (
        sourceChoice(
            visibility,
            "type",
            SourceRequestPath.VISIBILITY_TYPE,
            SourceRequestRule.VISIBILITY_TYPE,
            listOf("any", "exact"),
            index,
        )
    ) {
        "any" -> sourceFields(visibility, SourceRequestPath.VISIBILITY, setOf("type"), index)
        else -> {
            sourceFields(visibility, SourceRequestPath.VISIBILITY, setOf("type", "values"), index)
            sourceChoices(
                visibility,
                "values",
                SourceRequestPath.VISIBILITY_VALUES,
                SourceRequestRule.VISIBILITY,
                listOf("public", "protected", "internal", "private", "local"),
                index,
            )
        }
    }
}

private fun validateSourceText(root: JsonObject) {
    val projection = sourceObjectAt(sourceRequired(root, "text", SourceRequestPath.TEXT), SourceRequestPath.TEXT)
    when (
        sourceChoice(
            projection,
            "type",
            SourceRequestPath.TEXT_TYPE,
            SourceRequestRule.TEXT_TYPE,
            listOf("complete", "none", "window"),
        )
    ) {
        "window" -> {
            sourceFields(projection, SourceRequestPath.TEXT, setOf("type", "beforeLines", "afterLines"))
            sourceNumber(
                projection,
                "beforeLines",
                SourceRequestPath.BEFORE_LINES,
                0,
                MAX_SOURCE_READ_LINE_COUNT.toLong(),
                SourceRequestRule.LINE_COUNT,
            )
            sourceNumber(
                projection,
                "afterLines",
                SourceRequestPath.AFTER_LINES,
                0,
                MAX_SOURCE_READ_LINE_COUNT.toLong(),
                SourceRequestRule.LINE_COUNT,
            )
        }
        else -> sourceFields(projection, SourceRequestPath.TEXT, setOf("type"))
    }
}

private fun validateSourcePage(root: JsonObject) {
    val page = sourceObjectAt(sourceRequired(root, "page", SourceRequestPath.PAGE), SourceRequestPath.PAGE)
    when (
        sourceChoice(
            page,
            "type",
            SourceRequestPath.PAGE_TYPE,
            SourceRequestRule.PAGE_TYPE,
            listOf("first", "continue"),
        )
    ) {
        "first" -> sourceFields(page, SourceRequestPath.PAGE, setOf("type"))
        else -> {
            sourceFields(page, SourceRequestPath.PAGE, setOf("type", "continuation"))
            val token =
                sourceString(
                    sourceRequired(page, "continuation", SourceRequestPath.CONTINUATION),
                    SourceRequestPath.CONTINUATION,
                )
            if (!isSourceContinuationSyntax(token))
                rejectSourceField(SourceRequestPath.CONTINUATION, SourceRequestRule.CONTINUATION_FORMAT)
        }
    }
}

private fun String.role(): SourceReferenceRole =
    when (this) {
        "candidate" -> SourceReferenceRole.CANDIDATE
        "symbol" -> SourceReferenceRole.SYMBOL
        else -> SourceReferenceRole.SOURCE
    }

private fun sourceRoot(element: JsonElement): JsonObject {
    val root = sourceObjectAt(element, SourceRequestPath.DOCUMENT)
    if ("symbol" in root) {
        val descriptor = SourceReadSimpleRequest.serializer().descriptor
        sourceFields(root, SourceRequestPath.DOCUMENT,
            (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet())
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

private fun decodeSimpleSource(root: JsonObject, json: Json): SourceReadRequest {
    val raw = sourceString(sourceRequired(root, "symbol", SourceRequestPath.SYMBOL), SourceRequestPath.SYMBOL)
    when (val parsed = ExactSymbolSelector.parse(raw)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> throw SourceRequestSerializationException(
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
            .decodeFromJsonElement(SourceReadSimpleRequest.serializer(), root).canonical()
    } catch (_: SerializationException) {
        throw SourceRequestSerializationException(
            SourceReadFailureDetail.RequestRejected(SourceRequestField(SourceRequestPath.DOCUMENT), SourceRequestRule.INVALID_JSON)
        )
    }
}
