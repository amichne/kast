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
sealed interface KastScopeMutationResponse

@Serializable
@SerialName("SCOPE_MUTATION_SUCCESS")
data class KastScopeMutationSuccessResponse(
    val ok: Boolean,
    val operation: KastScopeMutationOperation,
    val applied: Boolean,
    val affectedFiles: List<String>,
    val createdFiles: List<String> = emptyList(),
    val editCount: Int,
    val importChanges: Int,
    val diagnostics: KastDiagnosticsSummary,
    val placement: KastResolvedPlacement? = null,
    val logFile: String,
) : KastScopeMutationResponse

@Serializable
@SerialName("SCOPE_MUTATION_FAILURE")
data class KastScopeMutationFailureResponse(
    val ok: Boolean = false,
    val operation: KastScopeMutationOperation,
    val stage: String,
    val message: String,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastScopeMutationResponse
