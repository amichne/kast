package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.McpWorkspaceOperationClient
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.appserver.mcpWorkspaceOperationClient
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliFailureCause
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.configuredExistingIdeClient
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.cli.ManagedWorkspaceQuery
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** Semantic input is admitted before this boundary. Each request owns its lease and preparation jobs. */
internal fun invokeManagedTool(
    context: InstalledClientContext,
    directory: Path,
    admission: WorkspaceOperationAdmission,
    execute: (CanonicalRoot, ExistingIdeCliCapabilities) -> CliExit,
): CliExit {
    val root =
        when (val discovered = FilesystemCanonicalRootDiscovery.discover(directory)) {
            is CanonicalRootDiscovery.Rejected -> return managedWorkspaceRejected(ContainerFailure.INVALID_WORKSPACE)
            is CanonicalRootDiscovery.Discovered -> discovered.root
        }
    val opened =
        when (admission) {
            WorkspaceOperationAdmission.PREPARE ->
                ManagedWorkspaceQuery.open(context.installation, root, context.environment)
            WorkspaceOperationAdmission.EXISTING_AUTHORITY ->
                ManagedWorkspaceQuery.openExisting(context.installation, root, context.environment)
        }
    val workspace =
        when (opened) {
            is Refinement.Rejected -> return managedWorkspaceRejected(opened.failure)
            is Refinement.Refined -> opened.value
        }
    return workspace.use {
        // The shared workspace lease now excludes retirement. Recheck the Control after acquiring it.
        if (context.observe() != InstalledToolAdmission.AVAILABLE)
            return@use io.github.amichne.kast.cli.boundaryExit(CliBoundaryExitStatus.RUNTIME, "installation-shutdown")
        val client =
            when (val selected = ManagedToolClient.admit(root, workspace, admission)) {
                is Refinement.Rejected -> return@use selected.failure
                is Refinement.Refined -> selected.value
            }
        client.use { execute(root, it.capabilities) }
    }
}

private class ManagedToolClient
private constructor(
    private val owner: McpWorkspaceOperationClient,
    val capabilities: ExistingIdeCliCapabilities,
) : AutoCloseable {
    override fun close() = owner.close()

    companion object {
        fun admit(
            root: CanonicalRoot,
            workspace: ManagedWorkspaceQuery,
            admission: WorkspaceOperationAdmission,
        ): Refinement<ManagedToolClient, CliExit.BoundaryRejected> =
            when (admission) {
                WorkspaceOperationAdmission.EXISTING_AUTHORITY ->
                    Refinement.Refined(existing(workspace.home, workspace.environment))
                WorkspaceOperationAdmission.PREPARE ->
                    when (
                        val prepared = workspace.prepare { remaining ->
                            prepare(root, workspace.home, workspace.environment, remaining)
                        }
                    ) {
                        is Refinement.Rejected -> Refinement.Rejected(managedWorkspaceRejected(prepared.failure))
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
        ): Refinement<ManagedToolClient, CliExit.BoundaryRejected> {
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
                    is Refinement.Rejected -> Refinement.Rejected(managedPreparationRejected(prepared.failure))
                    is Refinement.Refined -> {
                        val client = compose(owner, prepared.value, home, bounded)
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
        ) =
            ManagedToolClient(
                owner,
                ExistingIdeCliCapabilities(
                    FilesystemCanonicalRootDiscovery,
                    configuredExistingIdeClient(home, environment),
                    read,
                ),
            )
    }
}

private fun managedWorkspaceRejected(failure: ContainerFailure): CliExit.BoundaryRejected =
    CliExit.BoundaryRejected(
        CliBoundaryExitStatus.RUNTIME,
        managedWorkspaceRejection.create(
            ManagedWorkspaceRejection(
                failure,
                if (failure == ContainerFailure.RUNTIME_UNAVAILABLE) "kast upgrade --repair" else null,
            )
        ),
    )

private fun managedPreparationRejected(failure: DaemonOperationFailure): CliExit.BoundaryRejected =
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
