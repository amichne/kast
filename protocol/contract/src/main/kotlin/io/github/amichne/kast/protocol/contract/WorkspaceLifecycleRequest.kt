package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public input excludes coordinator identity, launch commands and authorization assertions. */
@Serializable
sealed interface WorkspaceLifecycleRequest : OperationRequest {
    @Serializable @SerialName("inspect") data object Inspect : WorkspaceLifecycleRequest

    @Serializable
    @SerialName("open")
    data class Open(val root: String, val requestId: String) : WorkspaceLifecycleRequest

    @Serializable
    @SerialName("present")
    data class Present(val target: IdeProjectTarget, val requestId: String) : WorkspaceLifecycleRequest

    @Serializable
    @SerialName("release")
    data class Release(val target: IdeProjectTarget, val requestId: String) : WorkspaceLifecycleRequest

    @Serializable
    @SerialName("close")
    data class Close(val target: IdeProjectTarget, val requestId: String) : WorkspaceLifecycleRequest

    @Serializable
    @SerialName("request_user_close")
    data class RequestUserClose(val target: IdeProjectTarget, val requestId: String) : WorkspaceLifecycleRequest

    @Serializable
    @SerialName("status")
    data class Status(val host: String, val requestId: String) : WorkspaceLifecycleRequest
}
