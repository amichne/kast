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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.*

internal sealed interface PublicToolDocument { val verbose: Boolean }

@Serializable
internal enum class PublicToolNameMatch {
    @SerialName("EXACT") EXACT,
    @SerialName("FUZZY") FUZZY,
}

@Serializable
internal enum class PublicToolDeclarationKinds {
    @SerialName("CLASS") CLASS,
    @SerialName("FUNCTION") FUNCTION,
    @SerialName("PROPERTY") PROPERTY,
    @SerialName("TYPE_ALIAS") TYPE_ALIAS,
}

@Serializable
internal enum class PublicToolFields {
    @SerialName("NAME") NAME,
    @SerialName("LOCATION") LOCATION,
    @SerialName("SIGNATURE") SIGNATURE,
    @SerialName("SOURCE") SOURCE,
}

@Serializable
internal enum class PublicToolValues {
    @SerialName("PUBLIC") PUBLIC,
    @SerialName("PROTECTED") PROTECTED,
    @SerialName("INTERNAL") INTERNAL,
    @SerialName("PRIVATE") PRIVATE,
    @SerialName("LOCAL") LOCAL,
}

@Serializable
internal enum class PublicToolField {
    @SerialName("NAME") NAME,
    @SerialName("KIND") KIND,
    @SerialName("FILE") FILE,
}

@Serializable
internal enum class PublicToolOperator {
    @SerialName("EQUALS") EQUALS,
    @SerialName("NOT_EQUALS") NOT_EQUALS,
    @SerialName("STARTS_WITH") STARTS_WITH,
    @SerialName("ENDS_WITH") ENDS_WITH,
}

@Serializable
internal enum class PublicToolRelation {
    @SerialName("REFERENCES") REFERENCES,
    @SerialName("CALLERS") CALLERS,
    @SerialName("CALLEES") CALLEES,
    @SerialName("IMPLEMENTATIONS") IMPLEMENTATIONS,
    @SerialName("INHERITORS") INHERITORS,
    @SerialName("OVERRIDES") OVERRIDES,
    @SerialName("TYPE_USES") TYPE_USES,
}

@Serializable
internal enum class PublicToolRetention {
    @SerialName("DISCARD") DISCARD,
    @SerialName("RETAIN") RETAIN,
}

@Serializable
internal sealed interface PublicToolOutput

@Serializable
internal sealed interface PublicToolReadResultOutput

@Serializable
internal sealed interface PublicToolPredicate

@Serializable
internal sealed interface PublicToolWalkStrategy

@Serializable
internal sealed interface PublicToolScope

@Serializable
internal sealed interface PublicToolCompositionInput

@Serializable
internal sealed interface PublicToolSource

@Serializable
internal sealed interface PublicToolStep

@Serializable
internal sealed interface PublicToolJoinMode

@Serializable
internal sealed interface PublicToolAction

@Serializable
internal sealed interface PublicToolRetainedInput

@Serializable
@SerialName("SYMBOL_REFS")
internal data class PublicToolReferenceSource(
    val symbolRefs: BoundedProtocolList<ProtocolText>,
) : PublicToolCompositionInput, PublicToolSource

@Serializable
@SerialName("SYMBOLS")
internal data class PublicToolSymbolsOutput(
    val fields: BoundedProtocolList<PublicToolFields>,
) : PublicToolOutput, PublicToolReadResultOutput

@Serializable
@SerialName("OCCURRENCES")
internal data object PublicToolOccurrencesOutput : PublicToolOutput, PublicToolReadResultOutput

@Serializable
@SerialName("TRAVERSAL_RECORDS")
internal data object PublicToolTraversalRecordsOutput : PublicToolOutput, PublicToolReadResultOutput

@Serializable
@SerialName("VISIBILITY")
internal data class PublicToolVisibilityPredicate(
    val values: BoundedProtocolList<PublicToolValues>,
) : PublicToolPredicate

@Serializable
@SerialName("PRIMITIVE")
internal data class PublicToolPrimitivePredicate(
    val field: PublicToolField,
    val operator: PublicToolOperator,
    val value: ProtocolText,
) : PublicToolPredicate

@Serializable
@SerialName("WHERE")
internal data class PublicToolWhere(
    val predicate: PublicToolPredicate,
) : PublicToolStep

@Serializable
@SerialName("EXPAND_RELATION")
internal data class PublicToolExpandRelation(
    val relation: PublicToolRelation,
) : PublicToolStep

@Serializable
@SerialName("BREADTH_FIRST")
internal data object PublicToolBreadthFirstStrategy : PublicToolWalkStrategy

@Serializable
@SerialName("BOUNDED_FAN_OUT")
internal data class PublicToolBoundedFanOutStrategy(
    val maximumEdgesPerNode: Int? = null,
) : PublicToolWalkStrategy

@Serializable
@SerialName("WALK")
internal data class PublicToolWalk(
    val relation: PublicToolRelation,
    val maximumDepth: ProtocolCount? = null,
    val strategy: PublicToolWalkStrategy? = null,
) : PublicToolStep

@Serializable
@SerialName("DISTINCT_SYMBOLS")
internal data object PublicToolDistinctSymbols : PublicToolStep

@Serializable
@SerialName("PROJECT_BINDING")
internal data class PublicToolProjectBinding(
    val name: QueryBindingNameDocument,
) : PublicToolStep

@Serializable
internal data class PublicToolExecutionBudget(
    val maxElapsedMs: Long? = null,
    val maxWorkUnits: Long? = null,
    val maxResults: Int? = null,
    val maxReturnedBytes: Long? = null,
)

@Serializable
@SerialName("CONCAT")
internal data class PublicToolConcat(
    val input: PublicToolCompositionInput,
) : PublicToolStep

@Serializable
@SerialName("INTERSECT")
internal data class PublicToolIntersect(
    val right: PublicToolRetainedInput,
) : PublicToolStep

@Serializable
@SerialName("UNION")
internal data class PublicToolUnion(
    val right: PublicToolRetainedInput,
) : PublicToolStep

@Serializable
@SerialName("DIFFERENCE")
internal data class PublicToolDifference(
    val right: PublicToolRetainedInput,
) : PublicToolStep

@Serializable
@SerialName("RESULT")
internal data class PublicToolResultSource(
    val result: QueryResultReference,
    val rowIds: BoundedProtocolList<QueryResultRowReference>? = null,
) : PublicToolCompositionInput, PublicToolSource, PublicToolRetainedInput

@Serializable
@SerialName("RUN")
internal data class PublicToolRunAction(
    val source: PublicToolSource,
    val steps: BoundedProtocolList<PublicToolStep>? = null,
    val output: PublicToolOutput? = null,
    val retention: PublicToolRetention? = null,
    val executionBudget: PublicToolExecutionBudget? = null,
) : PublicToolAction

@Serializable
@SerialName("RESUME")
internal data class PublicToolResumeAction(
    val continuation: QueryExecutionContinuation,
    val executionBudget: PublicToolExecutionBudget? = null,
) : PublicToolAction

@Serializable
@SerialName("READ_RESULT")
internal data class PublicToolReadResultAction(
    val result: QueryResultReference,
    val cursor: QueryResultCursor? = null,
    val output: PublicToolReadResultOutput? = null,
    val executionBudget: PublicToolExecutionBudget? = null,
) : PublicToolAction

@Serializable
@SerialName("INNER")
internal data class PublicToolInnerJoinMode(
    val leftName: QueryBindingNameDocument,
    val rightName: QueryBindingNameDocument,
) : PublicToolJoinMode

@Serializable
@SerialName("SEMI")
internal data object PublicToolSemiJoinMode : PublicToolJoinMode

@Serializable
@SerialName("ANTI")
internal data object PublicToolAntiJoinMode : PublicToolJoinMode

@Serializable
@SerialName("JOIN")
internal data class PublicToolJoin(
    val mode: PublicToolJoinMode,
    val right: PublicToolRetainedInput,
) : PublicToolStep

@Serializable
@SerialName("BINDING_ROWS")
internal data object PublicToolBindingRowsOutput : PublicToolOutput, PublicToolReadResultOutput

@Serializable
internal data class PublicToolQuerySymbols(
    val request: PublicToolAction,
    override val verbose: Boolean = false,
) : PublicToolDocument

@Serializable
internal data class PublicToolCheckDiagnostics(
    val relativePath: ProtocolText,
    val maxDiagnostics: Int? = null,
    val continuation: ProtocolText? = null,
    val executionBudget: PublicToolExecutionBudget? = null,
    override val verbose: Boolean = false,
) : PublicToolDocument

@Serializable
internal data class PublicToolAddDeclaration(
    val exactTarget: ProtocolText,
    val declaration: ProtocolText,
    override val verbose: Boolean = false,
) : PublicToolDocument

@Serializable
internal data class PublicToolReplaceBody(
    val exactTarget: ProtocolText,
    val body: ProtocolText,
    override val verbose: Boolean = false,
) : PublicToolDocument

internal object PublicToolDefaults {
    val nameMatch = PublicToolNameMatch.EXACT
    const val includeSubdirectories = true
    const val includeSubpackages = true
    val walkDepth = toolDefault(ProtocolCount.parse(1))
    const val maximumEdgesPerNode = 32
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
    val executionBudget = PublicToolExecutionBudget()
    val scope: PublicToolScope = PublicToolDirectoryScope(toolDefault(ProtocolText.parse(".")), true, sourceSets)
}

internal fun decodePublicTool(identity: PublicToolIdentity, raw: JsonElement, json: Json): PublicToolDocument = when (identity) {
    PublicToolIdentity.QUERY_SYMBOLS -> json.decodeFromJsonElement(PublicToolQuerySymbols.serializer(), raw)
    PublicToolIdentity.CHECK_DIAGNOSTICS -> json.decodeFromJsonElement(PublicToolCheckDiagnostics.serializer(), raw)
    PublicToolIdentity.ADD_DECLARATION -> json.decodeFromJsonElement(PublicToolAddDeclaration.serializer(), raw)
    PublicToolIdentity.REPLACE_BODY -> json.decodeFromJsonElement(PublicToolReplaceBody.serializer(), raw)
}

internal fun encodePublicTool(value: PublicToolDocument, json: Json): JsonElement = when (value) {
    is PublicToolQuerySymbols -> json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), value)
    is PublicToolCheckDiagnostics -> json.encodeToJsonElement(PublicToolCheckDiagnostics.serializer(), value)
    is PublicToolAddDeclaration -> json.encodeToJsonElement(PublicToolAddDeclaration.serializer(), value)
    is PublicToolReplaceBody -> json.encodeToJsonElement(PublicToolReplaceBody.serializer(), value)
}

private fun <T> toolDefault(value: Refinement<T, *>): T = when (value) {
    is Refinement.Refined -> value.value
    is Refinement.Rejected -> error("Invalid authored public tool default")
}
