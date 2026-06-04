package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class SymbolQueryMode {
    @SerialName("exact")
    EXACT,

    @SerialName("lexical")
    LEXICAL,

    @SerialName("structural")
    STRUCTURAL,

    @SerialName("graph")
    GRAPH,

    @SerialName("semantic")
    SEMANTIC,
}

@Serializable
enum class SymbolQueryDeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    FUNCTION,
    PROPERTY,
    TYPEALIAS,
    ENUM_CLASS,
    ENUM_ENTRY,
    CONSTRUCTOR,
}

@Serializable
enum class SymbolQueryVisibility {
    PUBLIC,
    INTERNAL,
    PROTECTED,
    PRIVATE,
    LOCAL,
}

@Serializable
enum class SymbolQueryUsageFacet {
    PUBLIC_API,
    INTERNAL_API,
    MODULE_PRIVATE,
    BRIDGE,
    BUILD_LOGIC,
}

@Serializable
enum class SymbolQueryGraphDirection {
    INCOMING,
    OUTGOING,
    BOTH,
}

@Serializable
enum class SymbolQueryEdgeKind {
    CALL,
    TYPE_REF,
    INHERITANCE,
    OVERRIDE,
    IMPORT,
    ANNOTATION,
    UNKNOWN,
}

@Serializable
enum class SymbolQuerySemanticProfile {
    @SerialName("identity")
    IDENTITY,

    @SerialName("behavior")
    BEHAVIOR,

    @SerialName("api_usage")
    API_USAGE,
}

@Serializable
enum class SymbolQueryFailureReason {
    INDEX_UNAVAILABLE,
    INVALID_FILTER,
    QUERY_TOO_BROAD,
    AMBIGUOUS_ANCHOR,
    ANCHOR_NOT_FOUND,
    SEMANTIC_UNAVAILABLE,
    TIMEOUT,
}

@Serializable
data class KastSymbolQueryFilters(
    val kinds: List<SymbolQueryDeclarationKind> = emptyList(),
    val visibility: List<SymbolQueryVisibility> = emptyList(),
    val modulePath: String? = null,
    val sourceSet: String? = null,
    val fileGlob: String? = null,
    val packagePrefix: String? = null,
    val fqNamePrefix: String? = null,
    val gradleProject: String? = null,
    val relativePathPrefix: String? = null,
    val productionOnly: Boolean = false,
    val excludePatterns: List<String> = emptyList(),
    val usageFacets: List<SymbolQueryUsageFacet> = emptyList(),
)

@Serializable
data class KastSymbolQueryAnchor(
    val fqName: String? = null,
    val symbol: String? = null,
    val filePath: String? = null,
    val offset: Int? = null,
)

@Serializable
data class KastSymbolQueryGraph(
    val direction: SymbolQueryGraphDirection = SymbolQueryGraphDirection.BOTH,
    val edgeKinds: List<SymbolQueryEdgeKind> = emptyList(),
    val depth: Int = 1,
    val maxEdgesPerResult: Int = 10,
)

@Serializable
data class KastSymbolQuerySemantic(
    val enabled: Boolean = false,
    val profile: SymbolQuerySemanticProfile = SymbolQuerySemanticProfile.IDENTITY,
    val maxCandidates: Int = 0,
)

@Serializable
data class KastSymbolQueryRequest(
    val workspaceRoot: String? = null,
    val query: String,
    val modes: List<SymbolQueryMode> = listOf(
        SymbolQueryMode.EXACT,
        SymbolQueryMode.LEXICAL,
        SymbolQueryMode.STRUCTURAL,
        SymbolQueryMode.GRAPH,
    ),
    val filters: KastSymbolQueryFilters = KastSymbolQueryFilters(),
    val anchor: KastSymbolQueryAnchor? = null,
    val graph: KastSymbolQueryGraph = KastSymbolQueryGraph(),
    val semantic: KastSymbolQuerySemantic = KastSymbolQuerySemantic(),
    val limit: Int = 25,
    val includeEvidence: Boolean = true,
    val includeNextRequests: Boolean = false,
)

@Serializable
sealed interface KastSymbolQueryResponse

@Serializable
@SerialName("SYMBOL_QUERY_SUCCESS")
data class KastSymbolQuerySuccessResponse(
    val ok: Boolean = true,
    val query: String,
    val availableSignals: AvailableSignals,
    val hardFilters: List<HardFilter>,
    val results: List<SymbolQueryResult>,
    val logFile: String,
) : KastSymbolQueryResponse

@Serializable
@SerialName("SYMBOL_QUERY_FAILURE")
data class KastSymbolQueryFailureResponse(
    val ok: Boolean = false,
    val reason: SymbolQueryFailureReason,
    val stage: String,
    val message: String,
    val query: String,
    val logFile: String,
) : KastSymbolQueryResponse

@Serializable
data class AvailableSignals(
    val exact: Boolean,
    val lexical: Boolean,
    val structural: Boolean,
    val graph: Boolean,
    val semantic: Boolean,
)

@Serializable
data class HardFilter(
    val field: String,
    val value: String,
    val source: String,
    val satisfiedSymbolically: Boolean,
)

@Serializable
data class SymbolQueryResult(
    val declaration: SymbolQueryDeclaration,
    val rank: SymbolQueryRank,
    val signals: SymbolQuerySignals,
    val nextRequests: SymbolQueryNextRequests? = null,
)

@Serializable
data class SymbolQueryDeclaration(
    val fqId: Long,
    val fqName: String,
    val simpleName: String,
    val kind: String,
    val visibility: String,
    val usageFacets: List<SymbolQueryUsageFacet> = emptyList(),
    val modulePath: String? = null,
    val sourceSet: String? = null,
    val file: SymbolQueryDeclarationFile,
    val declarationOffset: Int? = null,
)

@Serializable
data class SymbolQueryDeclarationFile(
    val prefixId: Int,
    val dirPath: String,
    val filename: String,
    val path: String,
)

@Serializable
data class SymbolQueryRank(
    val position: Int,
    val sortScore: Double,
    val components: SymbolQueryRankComponents,
)

@Serializable
data class SymbolQueryRankComponents(
    val exact: Double,
    val lexical: Double,
    val structural: Double,
    val graph: Double,
    val semantic: Double? = null,
)

@Serializable
data class SymbolQuerySignals(
    val exact: SymbolQueryExactSignal,
    val lexical: SymbolQueryLexicalSignal,
    val structural: SymbolQueryStructuralSignal,
    val graph: SymbolQueryGraphSignal,
    val semantic: SymbolQuerySemanticSignal,
)

@Serializable
data class SymbolQueryExactSignal(
    val matched: Boolean,
    val matches: List<SymbolQuerySignalMatch> = emptyList(),
)

@Serializable
data class SymbolQueryLexicalSignal(
    val matched: Boolean,
    val matches: List<SymbolQuerySignalMatch> = emptyList(),
)

@Serializable
data class SymbolQuerySignalMatch(
    val field: String,
    val term: String,
    val matchType: String,
    val evidence: String? = null,
)

@Serializable
data class SymbolQueryStructuralSignal(
    val matched: Boolean,
    val constraints: List<SymbolQueryStructuralConstraint> = emptyList(),
)

@Serializable
data class SymbolQueryStructuralConstraint(
    val field: String,
    val operator: String,
    val value: JsonElement,
    val source: String = "sqlite",
)

@Serializable
data class SymbolQueryGraphSignal(
    val matched: Boolean,
    val paths: List<SymbolQueryGraphPath> = emptyList(),
)

@Serializable
data class SymbolQueryGraphPath(
    val fromFqName: String? = null,
    val edgeKind: String,
    val toFqName: String,
    val sourceFile: String? = null,
    val sourceOffset: Int? = null,
    val depth: Int = 1,
)

@Serializable
data class SymbolQuerySemanticSignal(
    val available: Boolean,
    val matched: Boolean,
    val discoveryOnly: Boolean,
    val reason: String? = null,
)

@Serializable
data class SymbolQueryNextRequests(
    val symbolResolve: SymbolQueryNextRequest? = null,
    val symbolReferences: SymbolQueryNextRequest? = null,
    val symbolCallers: SymbolQueryNextRequest? = null,
    val rawResolve: SymbolQueryNextRequest? = null,
)

@Serializable
data class SymbolQueryNextRequest(
    val method: String,
    val request: JsonElement,
)
