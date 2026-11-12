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
                            selectLifecycleDeployment(IdeLifecycleDeployment.Headless(state.path), home, idea.path)
                    ) {
                        is Refinement.Rejected -> Refinement.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED)
                        is Refinement.Refined -> selected
                    }
            }
    }

internal fun admitSemanticDeployment(
    deployment: SelectedLifecycleDeployment,
    descriptor: ExistingIdeDescriptor,
): Refinement<ExistingIdeDescriptor, ExistingIdeFailure> =
    when (deployment) {
        is SelectedLifecycleDeployment.Headless -> {
            val identity = descriptor.application?.runtimeIdentity
            if (
                identity != null &&
                    managedIdentityMatches(deployment.installation, identity) &&
                    identity.pid == descriptor.hostPid
            )
                Refinement.Refined(descriptor)
            else Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
        }
    }
