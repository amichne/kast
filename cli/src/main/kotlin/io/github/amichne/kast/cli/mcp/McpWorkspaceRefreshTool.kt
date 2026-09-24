@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.McpWorkspaceOperationClient
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** Explicit file refresh for the one Gradle root that owns this MCP session. */
internal class McpWorkspaceRefreshTool(
    private val directory: Path,
    private val lifecycle: (WorkspaceLifecycleRequest) -> IdeLifecycleResult,
) {
    constructor(directory: Path, client: McpWorkspaceOperationClient) : this(directory, client::lifecycle)

    private data class IssuedRefresh(val target: IdeProjectTarget, val effect: WorkspaceRefreshEffect)

    private sealed interface RefreshDispatch {
        data class Ready(val requestId: String, val operation: WorkspaceLifecycleRequest) : RefreshDispatch

        data class Rejected(val failure: McpRefreshFailure) : RefreshDispatch
    }

    private val pending = mutableMapOf<String, IssuedRefresh>()

    val tool =
        McpSupplementalTool(
            name = "refresh_workspace",
            description =
                "Refresh the current repository's files in its already open IntelliJ project and wait for indexed " +
                    "readiness. Returns a request ID while pending; call again with that ID for status. " +
                    "Does not reload the Gradle model or save editor buffers.",
            inputSchema = refreshJson.encodeToJsonElement(McpRefreshInputSchema()),
            invoke = ::invoke,
            readOnly = false,
        )

    private fun invoke(arguments: JsonObject): CliExit {
        val input =
            try {
                refreshJson.decodeFromJsonElement(McpRefreshInput.serializer(), arguments)
            } catch (_: SerializationException) {
                return rejected(McpRefreshFailure.INVALID_REQUEST)
            }
        val root =
            (FilesystemCanonicalRootDiscovery.discover(directory) as? CanonicalRootDiscovery.Discovered)?.root?.path
                ?: return rejected(McpRefreshFailure.OUT_OF_SCOPE)
        val inspected = lifecycle(WorkspaceLifecycleRequest.Inspect)
        if (inspected !is IdeLifecycleResult.Inspected)
            return rejected(
                McpRefreshFailure.HOST_UNAVAILABLE,
                (inspected as? IdeLifecycleResult.Blocked)?.reason,
            )
        val targets = inspected.projects.map { it.target }.filter { it.root == root.toString() }
        if (targets.isEmpty()) return rejected(McpRefreshFailure.PROJECT_NOT_OPEN)
        if (targets.size != 1) return rejected(McpRefreshFailure.AMBIGUOUS_PROJECT)
        val target = targets.single()
        val dispatch =
            when (val admitted = dispatch(input, target)) {
                is RefreshDispatch.Ready -> admitted
                is RefreshDispatch.Rejected -> return rejected(admitted.failure)
            }
        val result = lifecycle(dispatch.operation)
        val projected = project(result, target, dispatch.requestId)
        if (result is IdeLifecycleResult.Pending && projected is CliExit.Qualified)
            pending[dispatch.requestId] = IssuedRefresh(target, WorkspaceRefreshEffect.FILE_REFRESH)
        else pending.remove(dispatch.requestId)
        return projected
    }

    private fun dispatch(input: McpRefreshInput, target: IdeProjectTarget): RefreshDispatch {
        val requestId = input.requestId ?: UUID.randomUUID().toString()
        if (runCatching { UUID.fromString(requestId) }.isFailure)
            return RefreshDispatch.Rejected(McpRefreshFailure.INVALID_REQUEST)
        if (input.requestId != null) {
            val issued = pending[requestId] ?: return RefreshDispatch.Rejected(McpRefreshFailure.UNKNOWN_REQUEST)
            if (issued != IssuedRefresh(target, WorkspaceRefreshEffect.FILE_REFRESH)) {
                pending.remove(requestId)
                return RefreshDispatch.Rejected(McpRefreshFailure.IDENTITY_MISMATCH)
            }
        } else if (pending.size >= MAX_PENDING) return RefreshDispatch.Rejected(McpRefreshFailure.CAPACITY_EXCEEDED)
        val operation =
            if (input.requestId == null)
                WorkspaceLifecycleRequest.Sync(target, requestId, WorkspaceRefreshEffect.FILE_REFRESH)
            else WorkspaceLifecycleRequest.Status(target.host, requestId)
        return RefreshDispatch.Ready(requestId, operation)
    }

    private fun project(result: IdeLifecycleResult, target: IdeProjectTarget, requestId: String): CliExit =
        when (result) {
            is IdeLifecycleResult.Synced ->
                if (result.target == target)
                    CliExit.Complete(
                        refreshDocument.create(McpRefreshResult.Complete(McpRefreshData(target, requestId)))
                    )
                else rejected(McpRefreshFailure.IDENTITY_MISMATCH)
            is IdeLifecycleResult.Pending ->
                if (result.requestId == requestId && result.host == target.host)
                    CliExit.Qualified(
                        refreshDocument.create(
                            McpRefreshResult.Pending(McpRefreshPendingData(target, requestId, result.stage))
                        )
                    )
                else rejected(McpRefreshFailure.IDENTITY_MISMATCH)
            is IdeLifecycleResult.Blocked -> rejected(McpRefreshFailure.LIFECYCLE_REJECTED, result.reason)
            is IdeLifecycleResult.Inspected,
            is IdeLifecycleResult.Opened,
            is IdeLifecycleResult.Presented,
            is IdeLifecycleResult.Configured,
            is IdeLifecycleResult.Released,
            is IdeLifecycleResult.Closed -> rejected(McpRefreshFailure.UNEXPECTED_RESULT)
        }

    private fun rejected(failure: McpRefreshFailure, cause: IdeLifecycleFailure? = null): CliExit.OperationRejected =
        CliExit.OperationRejected(refreshDocument.create(McpRefreshResult.Rejected(McpRefreshError(failure, cause))))
}

@Serializable private data class McpRefreshInput(val requestId: String? = null)

@Serializable
private data class McpRefreshInputSchema(
    val type: String = "object",
    val properties: McpRefreshInputProperties = McpRefreshInputProperties(),
    val additionalProperties: Boolean = false,
)

@Serializable
private data class McpRefreshInputProperties(val requestId: McpRefreshStringSchema = McpRefreshStringSchema())

@Serializable private data class McpRefreshStringSchema(val type: String = "string")

@Serializable
@JsonClassDiscriminator("status")
private sealed interface McpRefreshResult {
    @Serializable @SerialName("complete") data class Complete(val data: McpRefreshData) : McpRefreshResult

    @Serializable @SerialName("partial") data class Pending(val data: McpRefreshPendingData) : McpRefreshResult

    @Serializable @SerialName("rejected") data class Rejected(val error: McpRefreshError) : McpRefreshResult
}

@Serializable private data class McpRefreshData(val target: IdeProjectTarget, val requestId: String)

@Serializable
private data class McpRefreshPendingData(
    val target: IdeProjectTarget,
    val requestId: String,
    val stage: IdeLifecycleStage,
)

@Serializable private data class McpRefreshError(val code: McpRefreshFailure, val cause: IdeLifecycleFailure?)

@Serializable
private enum class McpRefreshFailure {
    INVALID_REQUEST,
    OUT_OF_SCOPE,
    HOST_UNAVAILABLE,
    PROJECT_NOT_OPEN,
    AMBIGUOUS_PROJECT,
    IDENTITY_MISMATCH,
    UNKNOWN_REQUEST,
    CAPACITY_EXCEEDED,
    LIFECYCLE_REJECTED,
    UNEXPECTED_RESULT,
}

private const val MAX_PENDING = 64

private val refreshJson = Json { encodeDefaults = true }
private val refreshDocument = CanonicalJsonDocument.generated(McpRefreshResult.serializer())
