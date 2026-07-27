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
data class KastDiagnosticsRequest(
    val workspaceRoot: String? = null,
    val filePaths: List<String>,
)


@Serializable
data class KastDiagnosticsQuery(
    val workspaceRoot: String,
    val filePaths: List<String>,
)


@Serializable
sealed interface KastDiagnosticsResponse

@Serializable
@SerialName("DIAGNOSTICS_SUCCESS")
data class KastDiagnosticsSuccessResponse(
    val ok: Boolean = true,
    val query: KastDiagnosticsQuery,
    val clean: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val diagnostics: List<Diagnostic>,
    val logFile: String,
) : KastDiagnosticsResponse

@Serializable
@SerialName("DIAGNOSTICS_FAILURE")
data class KastDiagnosticsFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastDiagnosticsQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastDiagnosticsResponse
