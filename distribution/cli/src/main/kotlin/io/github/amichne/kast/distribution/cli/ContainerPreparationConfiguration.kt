package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

internal fun containerPreparationConfiguration(
    installation: Path,
    workspace: Path,
    environment: Map<String, String>,
): Refinement<ResolvedKastConfiguration, ContainerFailure> =
    when (val parsed = InstalledWorkspaceConfigurationIngress.resolve(installation, workspace, environment)) {
        is Refinement.Rejected -> Refinement.Rejected(ContainerFailure.INVALID_CONFIGURATION)
        is Refinement.Refined -> parsed
    }
