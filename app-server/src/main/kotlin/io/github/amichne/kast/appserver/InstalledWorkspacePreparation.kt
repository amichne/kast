package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.appserver.runtime.JsonLineWorkspacePreparationObserver
import io.github.amichne.kast.appserver.runtime.PreparedWorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspacePreparations
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** One selected-IDE owner shared by management and semantic demand for this daemon lifetime. */
internal class InstalledWorkspacePreparation(
    options: InstalledCoordinatorOptions,
    owner: ThreadBindingOwner.Installation,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lifecycle = installedWorkspaceLifecycleClient(options.userHome, options.configuration.selectedIdeHome)
    private val native = ExistingIdeSocketClient(options.userHome, options.configuration.readLimits)
    val operations =
        WorkspacePreparations(
            CoroutineScope(ioDispatcher),
            { request -> runInterruptible(ioDispatcher) { lifecycle.execute(request, owner.installationId.value) } },
            observer = JsonLineWorkspacePreparationObserver(System.err),
            budget = OperationExecutionBudget.WORKSPACE_READINESS.value.milliseconds,
        )
    val demand: WorkspaceDemand =
        PreparedWorkspaceDemand(
            operations,
            {
                runInterruptible(ioDispatcher) {
                    lifecycle.execute(WorkspaceLifecycleRequest.Inspect, owner.installationId.value)
                }
            },
            { workspace, operation -> runInterruptible(ioDispatcher) { native.queryPrepared(workspace, operation) } },
        )
}
