package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerSocketPath
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import java.nio.file.Path

internal class InstalledCoordinatorOptions(
    val kast: Path,
    val userHome: Path,
    val environment: Map<String, String>,
    val installationRoot: Path,
    val socket: BrokerSocketPath,
    val publicEndpoint: BrokerPublicEndpoint,
    val serviceDirectory: Path,
    val readiness: BrokerServiceReadiness,
    val configuration: ResolvedKastConfiguration,
    val activitySink: BrokerStartupActivitySink,
) {
    /** Host admission is deferred; successful options must retain this process's readiness authority. */
    internal fun admitHost(demand: WorkspaceDemand): InstalledBrokerServerConfiguration =
        when (val admitted = InstalledBrokerServerConfiguration.admit(kast, userHome, environment)) {
            is InstalledBrokerServerConfiguration.Rejected -> admitted
            is InstalledBrokerServerConfiguration.Configured ->
                InstalledBrokerServerConfiguration.Configured(
                    admitted.options.copy(
                        readiness = readiness,
                        kastOptions = admitted.options.kastOptions.withWorkspaceDemand(demand),
                    )
                )
        }
}
