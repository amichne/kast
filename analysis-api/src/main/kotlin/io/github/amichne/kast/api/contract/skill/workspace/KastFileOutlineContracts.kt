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
data class KastFileOutlineRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
)


@Serializable
data class KastFileOutlineQuery(
    val workspaceRoot: String,
    val filePath: String,
)


@Serializable
sealed interface KastFileOutlineResponse

@Serializable
@SerialName("FILE_OUTLINE_SUCCESS")
data class KastFileOutlineSuccessResponse(
    val ok: Boolean = true,
    val query: KastFileOutlineQuery,
    val symbols: List<OutlineSymbol>,
    val logFile: String,
) : KastFileOutlineResponse

@Serializable
@SerialName("FILE_OUTLINE_FAILURE")
data class KastFileOutlineFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastFileOutlineQuery,
    val logFile: String,
) : KastFileOutlineResponse
