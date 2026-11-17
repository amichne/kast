package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.CoordinatorOperationExecutor
import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.workspaceAdmission
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.cli.ManagedWorkspaceQuery
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Retained clients carry Host authority. Every execution acquires and releases its own workspace lease. */
internal class ManagedCoordinatorOperations(private val context: InstalledClientContext) :
    CoordinatorOperationExecutor, AutoCloseable {
    private val execution = ReentrantLock()
    private val bindings = mutableMapOf<CanonicalRoot, Binding>()

    override suspend fun execute(root: CanonicalRoot, operation: DaemonOperationCall): DaemonOperationResult =
        runInterruptible(Dispatchers.IO) {
            execution.lockInterruptibly()
            try {
                executeAdmitted(root, operation)
            } finally {
                execution.unlock()
            }
        }

    private fun executeAdmitted(root: CanonicalRoot, operation: DaemonOperationCall): DaemonOperationResult {
        val admission = operation.workspaceAdmission()
        val retained = bindings[root].takeIf { admission == WorkspaceOperationAdmission.PREPARE }
        val result = executeWithLease(root, operation, admission, retained)
        if (retained == null || !result.preparedHostUnavailable()) return result

        bindings.remove(root)
        retained.client.close()
        return executeWithLease(root, operation, admission, null)
    }

    private fun executeWithLease(
        root: CanonicalRoot,
        operation: DaemonOperationCall,
        admission: WorkspaceOperationAdmission,
        retained: Binding?,
    ): DaemonOperationResult {
        val opened =
            if (admission == WorkspaceOperationAdmission.PREPARE && retained == null)
                ManagedWorkspaceQuery.open(context.installation, root, context.environment, ::inspectWorkspaceSeedHost)
            else ManagedWorkspaceQuery.openExisting(context.installation, root, context.environment)
        val workspace =
            when (opened) {
                is Refinement.Rejected -> return reject(DaemonOperationFailure.ManagedWorkspace(opened.failure))
                is Refinement.Refined -> opened.value
            }
        return workspace.use {
            if (context.observe() != InstalledToolAdmission.AVAILABLE)
                return@use reject(DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION))
            retained?.execute(root, operation, workspace) ?: firstRequest(root, operation, workspace, admission)
        }
    }

    private fun firstRequest(
        root: CanonicalRoot,
        operation: DaemonOperationCall,
        workspace: ManagedWorkspaceQuery,
        admission: WorkspaceOperationAdmission,
    ): DaemonOperationResult {
        val client =
            when (val selected = ManagedToolClient.admit(root, workspace, admission)) {
                is Refinement.Rejected -> return rejectedAdmission(selected.failure)
                is Refinement.Refined -> selected.value
            }
        if (admission == WorkspaceOperationAdmission.EXISTING_AUTHORITY)
            return client.use { it.capabilities.read.read(root, operation) }
        bindings[root] = Binding(workspace.home, workspace.environment, client)
        return client.capabilities.read.read(root, operation)
    }

    override fun close() = execution.withLock {
        bindings.values.forEach { it.client.close() }
        bindings.clear()
    }

    private data class Binding(
        val home: Path,
        val environment: Map<String, String>,
        val client: ManagedToolClient,
    ) {
        fun execute(
            root: CanonicalRoot,
            operation: DaemonOperationCall,
            lease: ManagedWorkspaceQuery,
        ): DaemonOperationResult =
            if (home != lease.home || environment != lease.environment)
                reject(DaemonOperationFailure.ManagedWorkspace(ContainerFailure.INVALID_CONFIGURATION))
            else client.capabilities.read.read(root, operation)
    }
}

private fun rejectedAdmission(failure: ManagedToolFailure): DaemonOperationResult =
    when (failure) {
        is ManagedToolFailure.Workspace -> reject(DaemonOperationFailure.ManagedWorkspace(failure.reason))
        is ManagedToolFailure.Preparation -> reject(failure.reason)
        ManagedToolFailure.Stopped,
        ManagedToolFailure.Unproven ->
            reject(DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION))
    }

private fun reject(failure: DaemonOperationFailure) =
    DaemonOperationResult.Rejected(DaemonOperationClientRejection.Server(failure))

/** Lifecycle inspection found that the retained Host disappeared before it received this operation. */
private fun DaemonOperationResult.preparedHostUnavailable(): Boolean {
    val server = (this as? DaemonOperationResult.Rejected)?.failure as? DaemonOperationClientRejection.Server
    val operation = server?.failure as? DaemonOperationFailure.Workspace
    return operation?.cause == WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.HOST_UNAVAILABLE)
}
