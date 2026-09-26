// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@file:Suppress("ConstructorParameterNaming")

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.serialization.SerializationException
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
internal enum class PublicToolRetention {
    @SerialName("discard") DISCARD,
    @SerialName("retain") RETAIN,
}

@Serializable(with = PublicToolScopeSerializer::class)
internal sealed interface PublicToolScope

@Serializable
internal sealed interface PublicToolSource

@Serializable
internal sealed interface PublicToolCompositionInput

@Serializable
internal sealed interface PublicToolRetainedInput

@Serializable
internal sealed interface PublicToolJoinMode

@Serializable
internal sealed interface PublicToolStep

@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("action")
internal sealed interface PublicToolAction

@Serializable
internal data class PublicToolDirectoryScope(
    val relative_directory_path: ProtocolText,
    val include_subdirectories: Boolean = true,
    val source_set_names: BoundedProtocolList<ProtocolText>? = null,
) : PublicToolScope

@Serializable
internal data class PublicToolPackageScope(
    val package_name: ProtocolText,
    val include_subpackages: Boolean = true,
    val source_set_names: BoundedProtocolList<ProtocolText>? = null,
) : PublicToolScope

@Serializable
@SerialName("search_declarations")
internal data class PublicToolSearchSource(
    val declaration_name: ProtocolText,
    val name_match: PublicToolNameMatch? = null,
    val declaration_kinds: BoundedProtocolList<PublicToolDeclarationKinds>? = null,
    val scope: PublicToolScope? = null,
) : PublicToolSource

@Serializable
@SerialName("all_declarations")
internal data class PublicToolAllSource(
    val declaration_kinds: BoundedProtocolList<PublicToolDeclarationKinds>? = null,
    val scope: PublicToolScope? = null,
) : PublicToolSource

@Serializable
@SerialName("symbol_refs")
internal data class PublicToolReferenceSource(
    val symbol_refs: BoundedProtocolList<ProtocolText>,
) : PublicToolSource, PublicToolCompositionInput

@Serializable
@SerialName("where")
internal data class PublicToolWhere(
    val predicate: QueryPredicateDocument,
) : PublicToolStep

@Serializable
@SerialName("expand_relation")
internal data class PublicToolExpandRelation(
    val relation: PublicToolRelation,
) : PublicToolStep

@Serializable
@SerialName("walk")
internal data class PublicToolWalk(
    val relation: PublicToolRelation,
    @SerialName("maximum_depth")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val maximumDepth: ProtocolCount,
    val strategy: TraversalStrategyDocument? = null,
) : PublicToolStep

@Serializable
@SerialName("distinct_symbols")
internal data object PublicToolDistinctSymbols : PublicToolStep

@Serializable
@SerialName("project_binding")
internal data class PublicToolProjectBinding(
    val name: QueryBindingNameDocument,
) : PublicToolStep

@Serializable
@SerialName("concat")
internal data class PublicToolConcat(
    val input: PublicToolCompositionInput,
) : PublicToolStep

@Serializable
@SerialName("intersect")
internal data class PublicToolIntersect(
    val right: PublicToolRetainedInput,
) : PublicToolStep

@Serializable
@SerialName("union")
internal data class PublicToolUnion(
    val right: PublicToolRetainedInput,
) : PublicToolStep

@Serializable
@SerialName("difference")
internal data class PublicToolDifference(
    val right: PublicToolRetainedInput,
) : PublicToolStep

@Serializable
@SerialName("result")
internal data class PublicToolResultSource(
    val result: QueryResultReference,
    val row_ids: BoundedProtocolList<QueryResultRowReference>? = null,
) : PublicToolSource, PublicToolCompositionInput, PublicToolRetainedInput

@Serializable
@SerialName("run")
internal data class PublicToolRunAction(
    val source: PublicToolSource,
    val steps: BoundedProtocolList<PublicToolStep>?,
    val output: QueryOutputDocument? = null,
    val retention: PublicToolRetention? = null,
    @SerialName("execution_budget")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val executionBudget: ExecutionBudgetDocument? = null,
) : PublicToolAction

@Serializable
@SerialName("resume")
internal data class PublicToolResumeAction(
    val continuation: QueryExecutionContinuation,
    @SerialName("execution_budget")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val executionBudget: ExecutionBudgetDocument? = null,
) : PublicToolAction

@Serializable
@SerialName("read_result")
internal data class PublicToolReadResultAction(
    val result: QueryResultReference,
    val cursor: QueryResultCursor? = null,
    val output: QueryOutputDocument? = null,
    @SerialName("execution_budget")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val executionBudget: ExecutionBudgetDocument? = null,
) : PublicToolAction

@Serializable
@SerialName("inner")
internal data class PublicToolInnerJoinMode(
    @SerialName("left_name")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val leftName: QueryBindingNameDocument,
    @SerialName("right_name")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val rightName: QueryBindingNameDocument,
) : PublicToolJoinMode

@Serializable
@SerialName("semi")
internal data object PublicToolSemiJoinMode : PublicToolJoinMode

@Serializable
@SerialName("anti")
internal data object PublicToolAntiJoinMode : PublicToolJoinMode

@Serializable
@SerialName("join")
internal data class PublicToolJoin(
    val mode: PublicToolJoinMode,
    val right: PublicToolRetainedInput,
) : PublicToolStep

@Serializable
internal data class PublicToolCheckDiagnostics(
    val relative_path: ProtocolText,
    val max_diagnostics: Int? = null,
    val continuation: ProtocolText? = null,
    @SerialName("execution_budget")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val executionBudget: ExecutionBudgetDocument? = null,
) : PublicToolDocument

@Serializable
internal data class PublicToolQuerySymbols(
    val request: PublicToolAction,
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
    val output: QueryOutputDocument.Symbols =
        QueryOutputDocument.Symbols(
            toolDefault(
                BoundedProtocolList.create(
                    listOf(
                        QuerySymbolFieldDocument.NAME,
                        QuerySymbolFieldDocument.LOCATION,
                    )
                )
            )
        )
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
