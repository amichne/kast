package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.HostedServiceObservation
import io.github.amichne.kast.appserver.ide.admitObservedHostedRoot
import io.github.amichne.kast.distribution.contract.HostedRegistryFailure
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.HostedServiceUnavailableFailure
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/** Registered roots are status inputs even when another process performed the semantic request. */
internal fun registeredHostedServices(
    registry: WorkspaceRegistryRead,
    registryPath: Path,
    observe: (List<CanonicalRoot>) -> List<HostedServiceObservation>,
): List<HostedServiceStatus> =
    when (registry) {
        is WorkspaceRegistryRead.Rejected ->
            listOf(HostedServiceStatus.RegistryUnavailable(registryPath.toString(), registry.failure.hostedFailure()))
        is WorkspaceRegistryRead.Read -> observeRegisteredRoots(registry.snapshot.workspaces, observe)
    }

private fun observeRegisteredRoots(
    workspaces: List<WorkspaceRegistration>,
    observe: (List<CanonicalRoot>) -> List<HostedServiceObservation>,
): List<HostedServiceStatus> {
    val roots = ArrayList<CanonicalRoot>()
    val rejected = ArrayList<HostedServiceStatus.Unavailable>()
    for (workspace in workspaces) {
        when (val admitted = admitObservedHostedRoot(workspace.root.path)) {
            is Refinement.Refined -> roots += admitted.value
            is Refinement.Rejected ->
                rejected +=
                    HostedServiceStatus.Unavailable(
                        workspace.root.path.toString(),
                        HostedServiceUnavailableFailure.CONFIGURATION_REJECTED,
                    )
        }
    }
    return rejected + observe(roots).map(::projectHostedService)
}

private fun EnrollmentFailure.hostedFailure(): HostedRegistryFailure =
    when (this) {
        EnrollmentFailure.PATH_REJECTED -> HostedRegistryFailure.PATH_REJECTED
        EnrollmentFailure.DOCUMENT_REJECTED -> HostedRegistryFailure.DOCUMENT_REJECTED
        EnrollmentFailure.WRITE_REJECTED -> HostedRegistryFailure.WRITE_REJECTED
        EnrollmentFailure.WORKSPACE_CONFLICT -> HostedRegistryFailure.WORKSPACE_CONFLICT
        EnrollmentFailure.CAPACITY_EXCEEDED -> HostedRegistryFailure.CAPACITY_EXCEEDED
    }
