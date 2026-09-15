package io.github.amichne.kast.appserver

import java.nio.file.Path
import kotlinx.coroutines.runBlocking

internal fun readAppServerStatus(
    kast: Path,
    command: BrokerServiceLaunchCommand,
    registry: WorkspaceEnrollmentStore,
): AppServerManagementResult {
    val observed = runBlocking { InstalledCoordinatorClient(kast).status(command) }
    if (observed is CoordinatorStatusRead.Rejected && observed.failure == WorkerControlFailure.PAYLOAD_LIMIT_EXCEEDED)
        return AppServerManagementResult.Rejected(AppServerManagementFailure.PAYLOAD_LIMIT_EXCEEDED)
    val protocol = observeProtocol(command, observed)
    val registered = registry.snapshot()
    val lifecycle = MacOsPersistentBrokerServiceHost().observeLifecycle(command)
    return AppServerManagementResult.Completed(
        statusDocument(command, observed, registered, protocol, lifecycle).document()
    )
}

private fun observeProtocol(command: BrokerServiceLaunchCommand, observed: CoordinatorStatusRead): AppServerEvidence {
    if (observed !is CoordinatorStatusRead.Observed || command.publicEndpoint is BrokerPublicEndpoint.Private)
        return AppServerEvidence.UNOBSERVED
    return when (
        runBlocking {
            NativeCodexReadiness.observe(command.publicSocket, BrokerOperationalLimits.readinessExchange.value)
        }
    ) {
        NativeCodexReadiness.READY -> AppServerEvidence.READY
        NativeCodexReadiness.REJECTED -> AppServerEvidence.UNAVAILABLE
    }
}

private fun statusDocument(
    command: BrokerServiceLaunchCommand,
    observed: CoordinatorStatusRead,
    registered: WorkspaceRegistryRead,
    protocol: AppServerEvidence,
    lifecycle: BrokerLifecycleObservation,
): AppServerStatusDocument {
    val state = observed.serviceState().name.lowercase()
    return AppServerStatusDocument(
        transport =
            if (observed is CoordinatorStatusRead.Observed) AppServerEvidence.READY else AppServerEvidence.UNAVAILABLE,
        publicEndpoint = endpointStatus(command.publicEndpoint, observed),
        upstream = UpstreamStatus(protocol),
        protocol = protocol,
        catalog = catalogEvidence(observed, protocol),
        lifecycle = lifecycle,
        coordinator =
            when (observed) {
                is CoordinatorStatusRead.Observed -> CoordinatorStatusPresentation(state, observed.snapshot.observed)
                is CoordinatorStatusRead.Rejected -> CoordinatorStatusPresentation(state, reason = observed.failure)
            },
        service =
            ServiceStatusPresentation(
                state,
                if (observed is CoordinatorStatusRead.Observed) "matched" else "unobserved",
            ),
        host =
            HostStatusPresentation(
                when (observed) {
                    is CoordinatorStatusRead.Observed -> observed.snapshot.hostAttachment.name.lowercase()
                    is CoordinatorStatusRead.Rejected -> "unobserved"
                }
            ),
        paths =
            StatusPaths(
                command.serviceLog.toString(),
                command.launchEnvironment.toString(),
                command.kast.parent.parent.resolve("config/environment").toString(),
                command.kast.parent.parent.resolve("config/workspaces.json").toString(),
            ),
        registry = registered.presentation(),
        enrollment =
            when (registered) {
                is WorkspaceRegistryRead.Read -> registered.snapshot.workspaces.singleOrNull()?.root?.path?.toString()
                is WorkspaceRegistryRead.Rejected -> "rejected"
            },
    )
}

private fun endpointStatus(endpoint: BrokerPublicEndpoint, observed: CoordinatorStatusRead): PublicEndpointStatus =
    PublicEndpointStatus(
        when (endpoint) {
            is BrokerPublicEndpoint.Private -> PublicEndpointKind.PRIVATE
            is BrokerPublicEndpoint.CodexControl -> PublicEndpointKind.CODEX_CONTROL
        },
        endpoint.path.toString(),
        if (observed is CoordinatorStatusRead.Observed) PublicEndpointOwnership.KAST
        else PublicEndpointOwnership.UNOBSERVED,
    )

private fun catalogEvidence(observed: CoordinatorStatusRead, protocol: AppServerEvidence): AppServerEvidence =
    if (
        protocol == AppServerEvidence.READY &&
            observed is CoordinatorStatusRead.Observed &&
            observed.snapshot.hostAttachment == CoordinatorHostAttachment.PREPARED
    )
        AppServerEvidence.READY
    else AppServerEvidence.UNOBSERVED

private fun WorkspaceRegistryRead.presentation(): RegistryStatusPresentation =
    when (this) {
        is WorkspaceRegistryRead.Read ->
            RegistryStatusPresentation(
                if (snapshot.workspaces.isEmpty()) "empty" else "registered",
                snapshot.revision.value,
                snapshot.workspaces.size,
                snapshot.workspaces.map { RegisteredWorkspaceStatus(it.id.value, it.root.path.toString()) },
            )
        is WorkspaceRegistryRead.Rejected -> RegistryStatusPresentation("rejected", reason = failure)
    }

private fun CoordinatorStatusRead.serviceState(): PassiveServiceState =
    when (this) {
        is CoordinatorStatusRead.Observed -> PassiveServiceState.READY
        is CoordinatorStatusRead.Rejected ->
            when (failure) {
                WorkerControlFailure.UNAVAILABLE,
                WorkerControlFailure.DEADLINE_EXCEEDED -> PassiveServiceState.UNAVAILABLE
                WorkerControlFailure.PAYLOAD_LIMIT_EXCEEDED,
                WorkerControlFailure.ISOLATED_RUNTIME_RETIRED,
                WorkerControlFailure.INVALID_REQUEST,
                WorkerControlFailure.IDENTITY_REJECTED,
                WorkerControlFailure.SERVICE_IDENTITY_REJECTED,
                WorkerControlFailure.WORKSPACE_CONFIGURATION_REJECTED,
                WorkerControlFailure.COORDINATOR_IDENTITY_REJECTED,
                WorkerControlFailure.WORKER_BINDING_IDENTITY_REJECTED,
                WorkerControlFailure.REGISTRATION_REJECTED,
                WorkerControlFailure.RECEIPT_REJECTED,
                WorkerControlFailure.LIFECYCLE_TRANSITION,
                WorkerControlFailure.RECOVERY_REQUIRED,
                WorkerControlFailure.CAPACITY_REJECTED,
                WorkerControlFailure.STARTUP_REJECTED,
                WorkerControlFailure.RETIREMENT_UNPROVEN -> PassiveServiceState.REJECTED
            }
    }
