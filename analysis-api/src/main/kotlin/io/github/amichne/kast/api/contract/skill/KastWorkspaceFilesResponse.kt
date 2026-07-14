package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastWorkspaceFilesResponse

@Serializable
@SerialName("WORKSPACE_FILES_SUCCESS")
data class KastWorkspaceFilesSuccessResponse(
    val ok: Boolean = true,
    val query: KastWorkspaceFilesQuery,
    val modules: List<WorkspaceModule>,
    val snapshotToken: String,
    val schemaVersion: Int,
    val logFile: String,
) : KastWorkspaceFilesResponse

@Serializable
@SerialName("WORKSPACE_FILES_FAILURE")
data class KastWorkspaceFilesFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWorkspaceFilesQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastWorkspaceFilesResponse
