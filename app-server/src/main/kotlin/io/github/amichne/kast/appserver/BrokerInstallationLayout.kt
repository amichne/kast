package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.CoordinatorHostProfile
import io.github.amichne.kast.distribution.contract.InstallationLayout
import java.nio.file.Path

/** Physical executable selection determines the installed root for child launches and routing. */
internal class BrokerInstallationLayout
private constructor(
    val installation: InstallationLayout,
    val hostProfile: CoordinatorHostProfile,
) {
    val root: Path = installation.version.path
    val state: Path = installation.state.directory
    val broker: Path = installation.state.broker(hostProfile)
    val readiness: Path = installation.state.readiness(hostProfile)
    val run: Path = installation.state.run.directory
    val privatePublicSocket: Path = BrokerEndpointAliases.transportPath(installation.state.run.coordinatorSocket)
    val upstreamSocket: Path = BrokerUpstreamDirectories.transportPath(installation.state.run.upstreamSocket)

    companion object {
        /** The caller has established the physical installed executable and selected host home. */
        fun from(kast: Path, codexHome: Path): BrokerInstallationLayout =
            BrokerInstallationLayout(
                InstallationLayout.ofKastExecutable(kast),
                CoordinatorHostProfile.of(codexHome.toString()),
            )
    }
}
