package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.protocol.ApiErrorResponse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class KastDiscoverRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val line: Int? = null,
    val codeSnippet: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val maxResults: Int = 10,
    val includeDeclarationScope: Boolean = false,
)


@Serializable
data class KastDiscoverQuery(
    val workspaceRoot: String,
    val symbol: String,
    val fileHint: String? = null,
    val line: Int? = null,
    val codeSnippet: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val maxResults: Int = 10,
    val includeDeclarationScope: Boolean = false,
)


@Serializable
data class KastDiscoveryCandidate(
    val rank: Int,
    val confidence: Double,
    val symbol: Symbol,
    val selectorHandle: String,
    val reasons: List<String>,
    val resolveParams: KastResolveParams,
    val nextRequest: KastNextRequest,
)


@Serializable
data class KastCandidate(
    val line: Int,
    val column: Int,
    val context: String,
)


@Serializable
sealed interface KastDiscoverResponse

@Serializable
@SerialName("DISCOVER_SUCCESS")
data class KastDiscoverSuccessResponse(
    val ok: Boolean = true,
    val query: KastDiscoverQuery,
    val candidates: List<KastDiscoveryCandidate>,
    val page: PageInfo? = null,
    val logFile: String,
) : KastDiscoverResponse

@Serializable
@SerialName("DISCOVER_FAILURE")
data class KastDiscoverFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastDiscoverQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastDiscoverResponse
