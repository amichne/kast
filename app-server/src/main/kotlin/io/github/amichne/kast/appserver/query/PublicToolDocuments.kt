// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.*

internal sealed interface PublicToolDocument

@Serializable
internal enum class PublicToolNameMatch {
    @SerialName("exact") EXACT,
    @SerialName("fuzzy") FUZZY,
}

@Serializable
internal enum class PublicToolDeclarationKinds {
    @SerialName("class") CLASS,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type_alias") TYPE_ALIAS,
}

@Serializable
internal enum class PublicToolVisibilities {
    @SerialName("public") PUBLIC,
    @SerialName("protected") PROTECTED,
    @SerialName("internal") INTERNAL,
    @SerialName("private") PRIVATE,
    @SerialName("local") LOCAL,
}

@Serializable
internal enum class PublicToolRelation {
    @SerialName("references") REFERENCES,
    @SerialName("callers") CALLERS,
    @SerialName("callees") CALLEES,
    @SerialName("implementations") IMPLEMENTATIONS,
    @SerialName("inheritors") INHERITORS,
    @SerialName("overrides") OVERRIDES,
    @SerialName("type_uses") TYPE_USES,
}

@Serializable
internal enum class PublicToolReturnFields {
    @SerialName("name") NAME,
    @SerialName("location") LOCATION,
    @SerialName("signature") SIGNATURE,
    @SerialName("source") SOURCE,
}

@Serializable(with = PublicToolScopeSerializer::class)
internal sealed interface PublicToolScope

@Serializable
internal sealed interface PublicToolSource

@Serializable
internal sealed interface PublicToolStep

@Serializable
internal data class PublicToolDirectoryScope(
    val relative_directory_path: ProtocolText,
    val include_subdirectories: Boolean,
    val source_set_names: BoundedProtocolList<ProtocolText>?,
) : PublicToolScope

@Serializable
internal data class PublicToolPackageScope(
    val package_name: ProtocolText,
    val include_subpackages: Boolean,
    val source_set_names: BoundedProtocolList<ProtocolText>?,
) : PublicToolScope

@Serializable
@SerialName("search_declarations")
internal data class PublicToolSearchSource(
    val declaration_name: ProtocolText,
    val name_match: PublicToolNameMatch?,
    val declaration_kinds: BoundedProtocolList<PublicToolDeclarationKinds>?,
    val scope: PublicToolScope?,
) : PublicToolSource

@Serializable
@SerialName("all_declarations")
internal data class PublicToolAllSource(
    val declaration_kinds: BoundedProtocolList<PublicToolDeclarationKinds>?,
    val scope: PublicToolScope?,
) : PublicToolSource

@Serializable
@SerialName("symbol_refs")
internal data class PublicToolReferenceSource(
    val symbol_refs: BoundedProtocolList<ProtocolText>,
) : PublicToolSource

@Serializable
@SerialName("filter_visibility")
internal data class PublicToolFilterVisibility(
    val visibilities: BoundedProtocolList<PublicToolVisibilities>,
) : PublicToolStep

@Serializable
@SerialName("expand_relation")
internal data class PublicToolExpandRelation(
    val relation: PublicToolRelation,
) : PublicToolStep

@Serializable
@SerialName("distinct_symbols")
internal data object PublicToolDistinctSymbols : PublicToolStep

@Serializable
internal data class PublicToolCheckDiagnostics(
    val relative_path: ProtocolText,
    val max_diagnostics: Int?,
    val continuation: ProtocolText? = null,
    @SerialName("execution_budget")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val executionBudget: ExecutionBudgetDocument? = null,
) : PublicToolDocument

@Serializable
internal data class PublicToolQuerySymbols(
    val source: PublicToolSource,
    val steps: BoundedProtocolList<PublicToolStep>?,
    val return_fields: BoundedProtocolList<PublicToolReturnFields>?,
    val continuation: ProtocolText? = null,
    @SerialName("execution_budget")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val executionBudget: ExecutionBudgetDocument? = null,
) : PublicToolDocument

internal object PublicToolScopeSerializer : JsonContentPolymorphicSerializer<PublicToolScope>(PublicToolScope::class) {
    override fun selectDeserializer(element: JsonElement): kotlinx.serialization.DeserializationStrategy<PublicToolScope> = when {
        "relative_directory_path" in element.jsonObject -> PublicToolDirectoryScope.serializer()
        "package_name" in element.jsonObject -> PublicToolPackageScope.serializer()
        else -> throw SerializationException("scope requires a directory or package target")
    }
}

internal object PublicToolDefaults {
    val nameMatch = PublicToolNameMatch.EXACT
    val sourceSets = toolDefault(BoundedProtocolList.create(listOf(toolDefault(ProtocolText.parse("main")), toolDefault(ProtocolText.parse("test")))))
    val declarationKinds = toolDefault(BoundedProtocolList.create(listOf(PublicToolDeclarationKinds.CLASS, PublicToolDeclarationKinds.FUNCTION, PublicToolDeclarationKinds.PROPERTY, PublicToolDeclarationKinds.TYPE_ALIAS)))
    val returnFields = toolDefault(BoundedProtocolList.create(listOf(PublicToolReturnFields.NAME, PublicToolReturnFields.LOCATION)))
    val steps: BoundedProtocolList<PublicToolStep> = toolDefault(BoundedProtocolList.create(emptyList()))
    const val maxDiagnostics = 100
    val scope: PublicToolScope = PublicToolDirectoryScope(toolDefault(ProtocolText.parse(".")), true, sourceSets)
}

internal fun decodePublicTool(identity: PublicToolIdentity, raw: JsonElement, json: Json): PublicToolDocument = when (identity) {
    PublicToolIdentity.CHECK_DIAGNOSTICS -> json.decodeFromJsonElement(PublicToolCheckDiagnostics.serializer(), raw)
    PublicToolIdentity.QUERY_SYMBOLS -> json.decodeFromJsonElement(PublicToolQuerySymbols.serializer(), raw)
}

internal fun encodePublicTool(value: PublicToolDocument, json: Json): JsonElement = when (value) {
    is PublicToolCheckDiagnostics -> json.encodeToJsonElement(PublicToolCheckDiagnostics.serializer(), value)
    is PublicToolQuerySymbols -> json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), value)
}

private fun <T> toolDefault(value: Refinement<T, *>): T = when (value) {
    is Refinement.Refined -> value.value
    is Refinement.Rejected -> error("Invalid authored public tool default")
}
