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
sealed interface KastWriteAndValidateResponse

@Serializable
@SerialName("WRITE_AND_VALIDATE_SUCCESS")
data class KastWriteAndValidateSuccessResponse(
    val ok: Boolean,
    val query: KastWriteAndValidateQuery,
    val appliedEdits: Int,
    val importChanges: Int,
    val diagnostics: KastDiagnosticsSummary,
    val message: String? = null,
    val logFile: String,
) : KastWriteAndValidateResponse

@Serializable
@SerialName("WRITE_AND_VALIDATE_FAILURE")
data class KastWriteAndValidateFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWriteAndValidateFailureQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastWriteAndValidateResponse
