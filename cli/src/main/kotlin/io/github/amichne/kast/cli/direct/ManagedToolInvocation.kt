package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.McpWorkspaceOperationClient
import io.github.amichne.kast.appserver.PreparedMcpWorkspace
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.appserver.mcpWorkspaceOperationClient
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliFailureCause
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.configuredExistingIdeClient
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.cli.ControlStage
import io.github.amichne.kast.distribution.cli.ControlStageTrace
import io.github.amichne.kast.distribution.cli.ManagedWorkspaceQuery
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

internal sealed interface ManagedToolFailure {
    data class Workspace(val reason: ContainerFailure) : ManagedToolFailure

    data class Preparation(val reason: DaemonOperationFailure) : ManagedToolFailure
}

internal class ManagedToolClient
private constructor(
    private val owner: McpWorkspaceOperationClient,
    val capabilities: ExistingIdeCliCapabilities,
    val prepared: PreparedMcpWorkspace?,
) : AutoCloseable {
    override fun close() = owner.close()

    companion object {
        fun admit(
            root: CanonicalRoot,
            workspace: ManagedWorkspaceQuery,
            admission: WorkspaceOperationAdmission,
        ): Refinement<ManagedToolClient, ManagedToolFailure> =
            when (admission) {
                WorkspaceOperationAdmission.EXISTING_AUTHORITY ->
                    Refinement.Refined(existing(workspace.home, workspace.environment))
                WorkspaceOperationAdmission.PREPARE ->
                    when (
                        val prepared = workspace.prepare { remaining ->
                            ControlStageTrace.observeRefinement(ControlStage.WORKSPACE_PREPARATION) {
                                prepare(root, workspace.home, workspace.environment, remaining)
                            }
                        }
                    ) {
                        is Refinement.Rejected -> Refinement.Rejected(ManagedToolFailure.Workspace(prepared.failure))
                        is Refinement.Refined -> prepared.value
                    }
            }

        private fun existing(home: Path, environment: Map<String, String>): ManagedToolClient {
            val owner = mcpWorkspaceOperationClient(home, environment)
            return compose(owner, owner, home, environment)
        }

        private fun prepare(
            root: CanonicalRoot,
            home: Path,
            environment: Map<String, String>,
            remaining: ElapsedTimeAllowance,
        ): Refinement<ManagedToolClient, ManagedToolFailure> {
            val bounded =
                environment +
                    ("KAST_WORKSPACE_PREPARATION_WAIT" to
                        when (remaining) {
                            is ElapsedTimeAllowance.Bounded -> remaining.limit.value.toString()
                            ElapsedTimeAllowance.Unlimited -> "indefinite"
                        })
            val owner = mcpWorkspaceOperationClient(home, bounded)
            var transferred = false
            try {
                return when (val prepared = runBlocking { owner.prepared(root) }) {
                    is Refinement.Rejected -> Refinement.Rejected(ManagedToolFailure.Preparation(prepared.failure))
                    is Refinement.Refined -> {
                        val client = compose(owner, prepared.value.client, home, bounded, prepared.value)
                        transferred = true
                        Refinement.Refined(client)
                    }
                }
            } finally {
                if (!transferred) owner.close()
            }
        }

        private fun compose(
            owner: McpWorkspaceOperationClient,
            read: DaemonOperationClient,
            home: Path,
            environment: Map<String, String>,
            prepared: PreparedMcpWorkspace? = null,
        ) =
            ManagedToolClient(
                owner,
                ExistingIdeCliCapabilities(
                    FilesystemCanonicalRootDiscovery,
                    configuredExistingIdeClient(home, environment),
                    read,
                ),
                prepared,
            )
    }
}

internal fun managedWorkspaceRejected(failure: ContainerFailure): CliExit.BoundaryRejected =
    CliExit.BoundaryRejected(
        CliBoundaryExitStatus.RUNTIME,
        managedWorkspaceRejection.create(
            ManagedWorkspaceRejection(
                failure,
                if (failure == ContainerFailure.RUNTIME_UNAVAILABLE) "kast upgrade --repair" else null,
            )
        ),
        CliFailureCause.Runtime(
            DaemonOperationClientRejection.Server(DaemonOperationFailure.ManagedWorkspace(failure))
        ),
    )

internal fun managedPreparationRejected(failure: DaemonOperationFailure): CliExit.BoundaryRejected =
    CliExit.BoundaryRejected(
        CliBoundaryExitStatus.RUNTIME,
        managedPreparationRejection.create(ManagedPreparationRejection(failure)),
        CliFailureCause.Runtime(DaemonOperationClientRejection.Server(failure)),
    )

@Serializable
private data class ManagedWorkspaceRejection(
    val failure: ContainerFailure,
    val recovery: String?,
    @Required val status: String = "rejected",
    @Required val boundary: String = "managed_workspace",
)

@Serializable
private data class ManagedPreparationRejection(
    val failure: DaemonOperationFailure,
    @Required val status: String = "rejected",
    @Required val boundary: String = "managed_preparation",
)

private val managedWorkspaceRejection = CanonicalJsonDocument.generated(ManagedWorkspaceRejection.serializer())
private val managedPreparationRejection = CanonicalJsonDocument.generated(ManagedPreparationRejection.serializer())
