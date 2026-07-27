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
data class KastResolveRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val includeDeclarationScope: Boolean = false,
    val includeDocumentation: Boolean = false,
    val surroundingLines: Int? = null,
    val includeSurroundingMembers: Boolean = false,
)


@Serializable
data class KastResolveQuery(
    val workspaceRoot: String,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val includeDeclarationScope: Boolean = false,
    val includeDocumentation: Boolean = false,
    val surroundingLines: Int? = null,
    val includeSurroundingMembers: Boolean = false,
)


@Serializable
data class KastResolveParams(
    val workspaceRoot: String? = null,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
)

@Serializable
data class KastNextRequest(
    val method: String,
    val params: KastResolveParams,
)


@Serializable
data class KastSourceTextWindow(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

@Serializable
data class KastResolveContext(
    val surroundingText: KastSourceTextWindow? = null,
    val surroundingMembers: List<Symbol>? = null,
)
