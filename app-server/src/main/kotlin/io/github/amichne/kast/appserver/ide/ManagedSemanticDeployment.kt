package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationPathSelection
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/** Existing-reference reads keep the same managed ownership requirement as workspace preparation. */
internal fun configuredSemanticDeployment(
    home: Path,
    configuration: ResolvedKastConfiguration,
): Refinement<SelectedLifecycleDeployment, ExistingIdeFailure> =
    when (val state = configuration.managedHeadlessState) {
        ConfigurationPathSelection.OwnerDefault -> Refinement.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED)
        is ConfigurationPathSelection.Selected ->
            when (val idea = configuration.selectedIdeHome) {
                ConfigurationPathSelection.OwnerDefault ->
                    Refinement.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED)
                is ConfigurationPathSelection.Selected ->
                    when (
                        val selected =
                            selectLifecycleDeployment(
                                IdeLifecycleDeployment.Headless(
                                    state.path,
                                    configuration.hostIdleShutdown,
                                    configuration.hostMaxHeap,
                                    configuration.gradleImportEnvironment,
                                ),
                                home,
                                idea.path,
                            )
                    ) {
                        is Refinement.Rejected -> Refinement.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED)
                        is Refinement.Refined -> selected
                    }
            }
    }

internal fun admitSemanticDeployment(
    deployment: SelectedLifecycleDeployment,
    descriptor: ExistingIdeDescriptor,
): Refinement<ExistingIdeDescriptor, ExistingIdeFailure> {
    val rejected = Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
    val application = descriptor.application ?: return rejected
    val identity = application.runtimeIdentity
    return when (deployment) {
        is SelectedLifecycleDeployment.Headless ->
            if (
                managedIdentityMatches(deployment.installation, identity) &&
                    deployment.gradleEnvironment.matches(application.gradleImportEnvironmentIdentity) &&
                    identity.pid == descriptor.hostPid
            )
                Refinement.Refined(descriptor)
            else rejected
    }
}
