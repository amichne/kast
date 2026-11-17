package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.appserver.ide.ExistingIdeStage
import io.github.amichne.kast.appserver.ide.HostChildRetention
import io.github.amichne.kast.appserver.ide.HostIncarnationFailure
import io.github.amichne.kast.appserver.ide.IdeLifecycleClient
import io.github.amichne.kast.appserver.ide.observeExistingIdeStage
import io.github.amichne.kast.appserver.runtime.ExistingWorkspaceDemand
import io.github.amichne.kast.appserver.runtime.JsonLineWorkspacePreparationObserver
import io.github.amichne.kast.appserver.runtime.PreparedWorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspacePreparations
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Preparation and existing-authority access share one admitted Host configuration. */
internal class InstalledWorkspacePreparation(
    home: Path,
    configuration: ResolvedKastConfiguration,
    requester: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val lifecycle = installedWorkspaceLifecycleClient(home, configuration, HostChildRetention.Retained())
    private val native = ExistingIdeSocketClient(home, configuration)

    fun hostedServices(registryPath: Path): List<HostedServiceStatus> =
        registeredHostedServices(WorkspaceEnrollmentStore(registryPath).snapshot(), registryPath, native::observedHosts)

    val operations =
        WorkspacePreparations(
            CoroutineScope(ioDispatcher),
            { request ->
                runInterruptible(ioDispatcher) {
                    observeExistingIdeStage(ExistingIdeStage.LIFECYCLE_PREPARATION) {
                        lifecycle.execute(request, requester)
                    }
                }
            },
            observer = JsonLineWorkspacePreparationObserver(System.err, configuration.debug),
            budget = configuration.workspacePreparationWait.duration,
        )
    val existing: WorkspaceDemand = ExistingWorkspaceDemand(native, ioDispatcher)
    val demand =
        PreparedWorkspaceDemand(
            operations,
            {
                runInterruptible(ioDispatcher) {
                    observeExistingIdeStage(ExistingIdeStage.LIFECYCLE_INSPECTION) {
                        lifecycle.execute(WorkspaceLifecycleRequest.Inspect, requester)
                    }
                }
            },
            { workspace, operation -> runInterruptible(ioDispatcher) { native.queryPrepared(workspace, operation) } },
        )

    suspend fun observeHost(target: IdeProjectTarget) =
        runInterruptible(ioDispatcher) {
            (lifecycle as? IdeLifecycleClient)?.observeHost(target)
                ?: Refinement.Rejected(HostIncarnationFailure.INSPECTION_REJECTED)
        }
}
