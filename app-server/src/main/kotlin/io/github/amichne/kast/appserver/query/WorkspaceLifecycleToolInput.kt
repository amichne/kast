package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Tool-only presentation control; canonical lifecycle identity and approval remain unchanged. */
@Serializable
sealed interface WorkspaceLifecycleToolInput {
    val verbose: Boolean

    @Serializable
    @SerialName("inspect")
    data class Inspect(override val verbose: Boolean = false) : WorkspaceLifecycleToolInput

    @Serializable
    @SerialName("open")
    data class Open(val root: String, val requestId: String, override val verbose: Boolean = false) :
        WorkspaceLifecycleToolInput

    @Serializable
    @SerialName("present")
    data class Present(val target: IdeProjectTarget, val requestId: String, override val verbose: Boolean = false) :
        WorkspaceLifecycleToolInput

    @Serializable
    @SerialName("release")
    data class Release(val target: IdeProjectTarget, val requestId: String, override val verbose: Boolean = false) :
        WorkspaceLifecycleToolInput

    @Serializable
    @SerialName("close")
    data class Close(val target: IdeProjectTarget, val requestId: String, override val verbose: Boolean = false) :
        WorkspaceLifecycleToolInput

    @Serializable
    @SerialName("request_user_close")
    data class RequestUserClose(
        val target: IdeProjectTarget,
        val requestId: String,
        override val verbose: Boolean = false,
    ) : WorkspaceLifecycleToolInput

    @Serializable
    @SerialName("status")
    data class Status(val host: String, val requestId: String, override val verbose: Boolean = false) :
        WorkspaceLifecycleToolInput

    fun canonical(): WorkspaceLifecycleRequest =
        when (this) {
            is Inspect -> WorkspaceLifecycleRequest.Inspect
            is Open -> WorkspaceLifecycleRequest.Open(root, requestId)
            is Present -> WorkspaceLifecycleRequest.Present(target, requestId)
            is Release -> WorkspaceLifecycleRequest.Release(target, requestId)
            is Close -> WorkspaceLifecycleRequest.Close(target, requestId)
            is RequestUserClose -> WorkspaceLifecycleRequest.RequestUserClose(target, requestId)
            is Status -> WorkspaceLifecycleRequest.Status(host, requestId)
        }
}
