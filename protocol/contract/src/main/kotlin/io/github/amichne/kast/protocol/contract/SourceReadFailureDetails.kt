@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** A source cause cannot contain an admission wrapper or erase its finite diagnostic evidence. */
@Serializable(with = SourceReadCauseSerializer::class) sealed interface SourceReadCause : SourceReadFailure

@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
sealed interface SourceReadFailureDetail : SourceReadCause {
    @Serializable
    @SerialName("request-rejected")
    data class RequestRejected(val field: SourceRequestField, val reason: SourceRequestRule) : SourceReadFailureDetail {
        @kotlinx.serialization.Required
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
        val expected: SourceRequestExpectation = reason.expectation(field.path)
    }

    @Serializable
    @SerialName("reference-rejected")
    data class ReferenceRejected(val role: SourceReferenceRole, val reason: SourceReferenceFailure) :
        SourceReadFailureDetail

    @Serializable
    @SerialName("internal-contract-failure")
    data class InternalContractFailure(val obligation: SourceInternalObligation) : SourceReadFailureDetail
}

/** Field identity contains only authored paths and a bounded filter index, never caller text. */
@Serializable
data class SourceRequestField(
    val path: SourceRequestPath,
    val index: SourceFilterIndex? = null,
    val elementIndex: SourceValueIndex? = null,
)

@Serializable
enum class SourceRequestPath {
    @SerialName("$") DOCUMENT,
    @SerialName("symbol") SYMBOL,
    @SerialName("anchor") ANCHOR,
    @SerialName("anchor.type") ANCHOR_TYPE,
    @SerialName("anchor.selector") ANCHOR_SELECTOR,
    @SerialName("region") REGION,
    @SerialName("region.type") REGION_TYPE,
    @SerialName("region.kind") REGION_KIND,
    @SerialName("entities") ENTITIES,
    @SerialName("entities.type") ENTITIES_TYPE,
    @SerialName("entities.containment") CONTAINMENT,
    @SerialName("entities.filters") FILTERS,
    @SerialName("entities.filters[].type") FILTER_TYPE,
    @SerialName("entities.filters[].kinds") DECLARATION_KINDS,
    @SerialName("entities.filters[].visibility") VISIBILITY,
    @SerialName("entities.filters[].visibility.type") VISIBILITY_TYPE,
    @SerialName("entities.filters[].visibility.values") VISIBILITY_VALUES,
    @SerialName("text") TEXT,
    @SerialName("text.type") TEXT_TYPE,
    @SerialName("text.beforeLines") BEFORE_LINES,
    @SerialName("text.afterLines") AFTER_LINES,
    @SerialName("entityLimit") ENTITY_LIMIT,
    @SerialName("textByteLimit") TEXT_BYTE_LIMIT,
    @SerialName("page") PAGE,
    @SerialName("page.type") PAGE_TYPE,
    @SerialName("page.continuation") CONTINUATION,
    @SerialName("format") FORMAT,
    @SerialName("execution_budget") EXECUTION_BUDGET,
    @SerialName("execution_budget.max_elapsed_ms") BUDGET_ELAPSED,
    @SerialName("execution_budget.max_work_units") BUDGET_WORK,
    @SerialName("execution_budget.max_results") BUDGET_RESULTS,
    @SerialName("execution_budget.max_returned_bytes") BUDGET_BYTES,
}

@Serializable(with = SourceFilterIndexSerializer::class)
@JvmInline
value class SourceFilterIndex private constructor(val value: Int) {
    companion object {
        fun parse(value: Int): io.github.amichne.kast.kernel.Refinement<SourceFilterIndex, SourceRequestRule> =
            if (value in 0 until MAX_SOURCE_FILTERS)
                io.github.amichne.kast.kernel.Refinement.Refined(SourceFilterIndex(value))
            else io.github.amichne.kast.kernel.Refinement.Rejected(SourceRequestRule.FILTER_COUNT)
    }
}

internal object SourceFilterIndexSerializer :
    RefiningIntSerializer<SourceFilterIndex>(
        serialName = "io.github.amichne.kast.protocol.contract.SourceFilterIndex",
        minimum = 0,
        maximum = 3,
    ) {
    override fun raw(value: SourceFilterIndex): Int = value.value

    override fun refine(raw: Int): io.github.amichne.kast.kernel.Refinement<SourceFilterIndex, *> =
        SourceFilterIndex.parse(raw)
}

@Serializable(with = SourceValueIndexSerializer::class)
@JvmInline
value class SourceValueIndex private constructor(val value: Int) {
    companion object {
        fun parse(value: Int): io.github.amichne.kast.kernel.Refinement<SourceValueIndex, SourceRequestRule> =
            if (value in SourceDeclarationKindDocument.entries.indices)
                io.github.amichne.kast.kernel.Refinement.Refined(SourceValueIndex(value))
            else io.github.amichne.kast.kernel.Refinement.Rejected(SourceRequestRule.NONEMPTY_UNIQUE_VALUES)
    }
}

internal object SourceValueIndexSerializer :
    RefiningIntSerializer<SourceValueIndex>(
        serialName = "io.github.amichne.kast.protocol.contract.SourceValueIndex",
        minimum = 0,
        maximum = 4,
    ) {
    override fun raw(value: SourceValueIndex): Int = value.value

    override fun refine(raw: Int): io.github.amichne.kast.kernel.Refinement<SourceValueIndex, *> =
        SourceValueIndex.parse(raw)
}

@Serializable
enum class SourceRequestRule {
    @SerialName("required") REQUIRED,
    @SerialName("object-required") OBJECT_REQUIRED,
    @SerialName("string-required") STRING_REQUIRED,
    @SerialName("integer-required") INTEGER_REQUIRED,
    @SerialName("array-required") ARRAY_REQUIRED,
    @SerialName("unknown-field") UNKNOWN_FIELD,
    @SerialName("invalid-json") INVALID_JSON,
    @SerialName("anchor-type") ANCHOR_TYPE,
    @SerialName("region-type") REGION_TYPE,
    @SerialName("body-kind") BODY_KIND,
    @SerialName("enclosing-kind") ENCLOSING_KIND,
    @SerialName("entity-selection-type") ENTITY_SELECTION_TYPE,
    @SerialName("containment") CONTAINMENT,
    @SerialName("filter-type") FILTER_TYPE,
    @SerialName("declaration-kind") DECLARATION_KIND,
    @SerialName("visibility-type") VISIBILITY_TYPE,
    @SerialName("visibility") VISIBILITY,
    @SerialName("text-type") TEXT_TYPE,
    @SerialName("page-type") PAGE_TYPE,
    @SerialName("format") FORMAT,
    @SerialName("line-count-0-to-1000") LINE_COUNT,
    @SerialName("entity-count-1-to-1000") ENTITY_COUNT,
    @SerialName("entity-limit-not-applicable") ENTITY_LIMIT_NOT_APPLICABLE,
    @SerialName("positive-byte-count") BYTE_COUNT,
    @SerialName("filter-count-1-to-4") FILTER_COUNT,
    @SerialName("nonempty-unique-values") NONEMPTY_UNIQUE_VALUES,
    @SerialName("unique-filter-families") UNIQUE_FILTER_FAMILIES,
    @SerialName("continuation-format") CONTINUATION_FORMAT,
    @SerialName("execution-budget") EXECUTION_BUDGET,
}

@Serializable
enum class SourceReferenceRole {
    @SerialName("candidate") CANDIDATE,
    @SerialName("symbol") SYMBOL,
    @SerialName("source") SOURCE,
    @SerialName("continuation") CONTINUATION,
}

@Serializable
enum class SourceReferenceFailure {
    @SerialName("revalidation-wrong-kind") REVALIDATION_WRONG_KIND,
    @SerialName("revalidation-unretained") REVALIDATION_UNRETAINED,
    @SerialName("revalidation-expired") REVALIDATION_EXPIRED,
    @SerialName("revalidation-capacity") REVALIDATION_CAPACITY,
    @SerialName("revalidation-work-limit-reached") REVALIDATION_WORK_LIMIT_REACHED,
    @SerialName("revalidation-time-limit-reached") REVALIDATION_TIME_LIMIT_REACHED,
    @SerialName("revalidation-retired") REVALIDATION_RETIRED,
    @SerialName("revalidation-capture-unavailable") REVALIDATION_CAPTURE_UNAVAILABLE,
    @SerialName("revalidation-workspace-mismatch") REVALIDATION_WORKSPACE_MISMATCH,
    @SerialName("revalidation-owner-mismatch") REVALIDATION_OWNER_MISMATCH,
    @SerialName("revalidation-workspace-not-ready") REVALIDATION_WORKSPACE_NOT_READY,
    @SerialName("revalidation-basis-moved") REVALIDATION_BASIS_MOVED,
    @SerialName("revalidation-content-changed") REVALIDATION_CONTENT_CHANGED,
    @SerialName("revalidation-content-uncommitted") REVALIDATION_CONTENT_UNCOMMITTED,
    @SerialName("revalidation-scope-rejected") REVALIDATION_SCOPE_REJECTED,
    @SerialName("revalidation-declaration-missing") REVALIDATION_DECLARATION_MISSING,
    @SerialName("revalidation-unsupported-declaration") REVALIDATION_UNSUPPORTED_DECLARATION,
    @SerialName("revalidation-ambiguous") REVALIDATION_AMBIGUOUS,
    @SerialName("revalidation-compiler-identity-changed") REVALIDATION_COMPILER_IDENTITY_CHANGED,
    @SerialName("revalidation-compiler-unavailable") REVALIDATION_COMPILER_UNAVAILABLE,
    @SerialName("wrong-family") WRONG_FAMILY,
    @SerialName("malformed") MALFORMED,
    @SerialName("invalid-payload-encoding") INVALID_PAYLOAD_ENCODING,
    @SerialName("payload-digest-mismatch") PAYLOAD_DIGEST_MISMATCH,
    @SerialName("invalid-document") INVALID_DOCUMENT,
    @SerialName("foreign-workspace") FOREIGN_WORKSPACE,
    @SerialName("incompatible-authority") INCOMPATIBLE_AUTHORITY,
    @SerialName("stale-authority") STALE_AUTHORITY,
    @SerialName("unsupported-version") UNSUPPORTED_VERSION,
    @SerialName("live-authority-required") LIVE_AUTHORITY_REQUIRED,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("token-too-long") TOKEN_TOO_LONG,
    @SerialName("snapshot-rejected") SNAPSHOT_REJECTED,
    @SerialName("selector-rejected") SELECTOR_REJECTED,
    @SerialName("selector-too-deep") SELECTOR_TOO_DEEP,
}

@Serializable
enum class SourceInternalObligation {
    @SerialName("context-lease") CONTEXT_LEASE,
    @SerialName("snapshot-context") SNAPSHOT_CONTEXT,
    @SerialName("snapshot-scope") SNAPSHOT_SCOPE,
    @SerialName("anchor-snapshot") ANCHOR_SNAPSHOT,
    @SerialName("declaration-visibility") DECLARATION_VISIBILITY,
    @SerialName("request-refinement") REQUEST_REFINEMENT,
    @SerialName("result-projection") RESULT_PROJECTION,
    @SerialName("qualification-projection") QUALIFICATION_PROJECTION,
    @SerialName("provider-contract") PROVIDER_CONTRACT,
}

/** Legacy finite conditions retain their encoding; new causes are disjoint typed objects. */
object SourceReadCauseSerializer : KSerializer<SourceReadCause> {
    override val descriptor = buildClassSerialDescriptor("SourceReadCause")

    override fun serialize(encoder: Encoder, value: SourceReadCause) {
        when (value) {
            is SourceReadRejection -> encoder.encodeSerializableValue(SourceReadRejection.serializer(), value)
            is SourceReadFailureDetail -> encoder.encodeSerializableValue(SourceReadFailureDetail.serializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): SourceReadCause {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Source causes require JSON")
        val element = json.decodeJsonElement()
        val cause =
            if (element is JsonPrimitive) json.json.decodeFromJsonElement(SourceReadRejection.serializer(), element)
            else json.json.decodeFromJsonElement(SourceReadFailureDetail.serializer(), element)
        if (
            cause is SourceReadFailureDetail.RequestRejected &&
                cause.expected != cause.reason.expectation(cause.field.path)
        )
            throw SerializationException("Contradictory source request expectation")
        return cause
    }
}

/** Static alternatives and bounds are safe to publish even for malicious inputs. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
sealed interface SourceRequestExpectation {
    @Serializable @SerialName("rule") data object Rule : SourceRequestExpectation

    @Serializable
    @SerialName("alternatives")
    data class Alternatives(
        @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 5, uniqueItems = true) val values: List<String>
    ) : SourceRequestExpectation

    @Serializable
    @SerialName("bounds")
    data class Bounds(val minimum: Long, val maximum: Long) : SourceRequestExpectation
}

private fun SourceRequestRule.expectation(): SourceRequestExpectation =
    when (this) {
        SourceRequestRule.ANCHOR_TYPE -> SourceRequestExpectation.Alternatives(listOf("candidate", "symbol", "source"))
        SourceRequestRule.REGION_TYPE ->
            SourceRequestExpectation.Alternatives(listOf("anchor", "body", "file", "enclosing"))
        SourceRequestRule.BODY_KIND -> SourceRequestExpectation.Alternatives(listOf("callable", "class"))
        SourceRequestRule.ENCLOSING_KIND ->
            SourceRequestExpectation.Alternatives(listOf("declaration", "callable-body", "class-body"))
        SourceRequestRule.ENTITY_SELECTION_TYPE -> SourceRequestExpectation.Alternatives(listOf("none", "matching"))
        SourceRequestRule.CONTAINMENT -> SourceRequestExpectation.Alternatives(listOf("direct", "descendants"))
        SourceRequestRule.FILTER_TYPE ->
            SourceRequestExpectation.Alternatives(listOf("declaration", "parameters", "calls", "references"))
        SourceRequestRule.DECLARATION_KIND ->
            SourceRequestExpectation.Alternatives(
                listOf("classlike", "constructor", "function", "property", "type-alias")
            )
        SourceRequestRule.VISIBILITY_TYPE -> SourceRequestExpectation.Alternatives(listOf("any", "exact"))
        SourceRequestRule.VISIBILITY ->
            SourceRequestExpectation.Alternatives(listOf("public", "protected", "internal", "private", "local"))
        SourceRequestRule.TEXT_TYPE -> SourceRequestExpectation.Alternatives(listOf("complete", "none", "window"))
        SourceRequestRule.PAGE_TYPE -> SourceRequestExpectation.Alternatives(listOf("first", "continue"))
        SourceRequestRule.FORMAT -> SourceRequestExpectation.Alternatives(listOf("expanded", "compact"))
        SourceRequestRule.LINE_COUNT -> SourceRequestExpectation.Bounds(0, MAX_SOURCE_READ_LINE_COUNT.toLong())
        SourceRequestRule.ENTITY_COUNT -> SourceRequestExpectation.Bounds(1, MAX_SOURCE_READ_ENTITY_LIMIT.toLong())
        SourceRequestRule.ENTITY_LIMIT_NOT_APPLICABLE -> SourceRequestExpectation.Rule
        SourceRequestRule.BYTE_COUNT -> SourceRequestExpectation.Bounds(1, Long.MAX_VALUE)
        SourceRequestRule.FILTER_COUNT -> SourceRequestExpectation.Bounds(1, MAX_SOURCE_FILTERS.toLong())
        SourceRequestRule.REQUIRED,
        SourceRequestRule.OBJECT_REQUIRED,
        SourceRequestRule.STRING_REQUIRED,
        SourceRequestRule.INTEGER_REQUIRED,
        SourceRequestRule.ARRAY_REQUIRED,
        SourceRequestRule.UNKNOWN_FIELD,
        SourceRequestRule.INVALID_JSON,
        SourceRequestRule.NONEMPTY_UNIQUE_VALUES,
        SourceRequestRule.UNIQUE_FILTER_FAMILIES,
        SourceRequestRule.CONTINUATION_FORMAT,
        SourceRequestRule.EXECUTION_BUDGET -> SourceRequestExpectation.Rule
    }

private fun SourceRequestRule.expectation(path: SourceRequestPath): SourceRequestExpectation {
    if (this == SourceRequestRule.ENTITY_LIMIT_NOT_APPLICABLE) return SourceRequestExpectation.Rule
    val own = expectation()
    if (own != SourceRequestExpectation.Rule) return own
    return when (path) {
        SourceRequestPath.ANCHOR_TYPE -> SourceRequestRule.ANCHOR_TYPE.expectation()
        SourceRequestPath.REGION_TYPE -> SourceRequestRule.REGION_TYPE.expectation()
        SourceRequestPath.ENTITIES_TYPE -> SourceRequestRule.ENTITY_SELECTION_TYPE.expectation()
        SourceRequestPath.CONTAINMENT -> SourceRequestRule.CONTAINMENT.expectation()
        SourceRequestPath.FILTER_TYPE -> SourceRequestRule.FILTER_TYPE.expectation()
        SourceRequestPath.DECLARATION_KINDS -> SourceRequestRule.DECLARATION_KIND.expectation()
        SourceRequestPath.VISIBILITY_TYPE -> SourceRequestRule.VISIBILITY_TYPE.expectation()
        SourceRequestPath.VISIBILITY_VALUES -> SourceRequestRule.VISIBILITY.expectation()
        SourceRequestPath.TEXT_TYPE -> SourceRequestRule.TEXT_TYPE.expectation()
        SourceRequestPath.BEFORE_LINES,
        SourceRequestPath.AFTER_LINES -> SourceRequestRule.LINE_COUNT.expectation()
        SourceRequestPath.BUDGET_ELAPSED,
        SourceRequestPath.BUDGET_WORK,
        SourceRequestPath.BUDGET_BYTES -> SourceRequestExpectation.Bounds(1, Long.MAX_VALUE)
        SourceRequestPath.BUDGET_RESULTS -> SourceRequestExpectation.Bounds(1, Int.MAX_VALUE.toLong())
        SourceRequestPath.ENTITY_LIMIT -> SourceRequestRule.ENTITY_COUNT.expectation()
        SourceRequestPath.TEXT_BYTE_LIMIT -> SourceRequestRule.BYTE_COUNT.expectation()
        SourceRequestPath.PAGE_TYPE -> SourceRequestRule.PAGE_TYPE.expectation()
        SourceRequestPath.FORMAT -> SourceRequestRule.FORMAT.expectation()
        SourceRequestPath.FILTERS -> SourceRequestRule.FILTER_COUNT.expectation()
        else -> SourceRequestExpectation.Rule
    }
}

internal const val MAX_SOURCE_FILTERS = 4
