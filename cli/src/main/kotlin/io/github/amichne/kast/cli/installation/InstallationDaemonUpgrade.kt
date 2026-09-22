package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.InstalledDaemonUpgrade
import io.github.amichne.kast.appserver.InstalledUpgradePreparation

/** Effect boundary for the selected release's managed daemon admission. */
internal fun interface PriorDaemonUpgradeGateway {
    fun prepare(
        retirement: PriorRetirement,
        request: InstallationRequest,
        candidate: Sha256,
    ): InstalledUpgradePreparation
}

internal object NativePriorDaemonUpgradeGateway : PriorDaemonUpgradeGateway {
    override fun prepare(
        retirement: PriorRetirement,
        request: InstallationRequest,
        candidate: Sha256,
    ): InstalledUpgradePreparation =
        InstalledDaemonUpgrade.prepare(
            retirement.daemonExecutable,
            request.home.value,
            retirement.environment,
            candidate.value,
        )
}
