package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOperationalLimit
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationScope
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationUnit

/** Fixed CLI effect-boundary policies, shared by consumers and passive inspection. */
internal object CliOperationalLimits {
    const val maximumRequestDocumentBytes: Int = 4 * 1_024 * 1_024
    const val directLaunchTimeoutSeconds: Long = 5
    const val launchctlTimeoutSeconds: Long = 5
    const val processStopTimeoutSeconds: Long = 10
    const val maximumProjectStateBytes: Long = 4L * 1_024 * 1_024
    const val maximumBootstrapDocumentBytes: Int = 16 * 1_024
    const val maximumProgressLines: Int = 256
    const val progressHeartbeatMillis: Long = 5_000
    const val maximumEndpointPathBytes: Int = 103

    val declarations: List<ConfigurationOperationalLimit> = listOf(
        limit("cli.request.maximum_bytes", maximumRequestDocumentBytes.toLong(), ConfigurationUnit.BYTES, "maximumRequestDocumentBytes", ConfigurationScope.REQUEST),
        limit("cli.launch.wrapper_timeout", directLaunchTimeoutSeconds * 1_000, ConfigurationUnit.MILLISECONDS, "directLaunchTimeoutSeconds", ConfigurationScope.WORKSPACE),
        limit("cli.launchctl.timeout", launchctlTimeoutSeconds * 1_000, ConfigurationUnit.MILLISECONDS, "launchctlTimeoutSeconds", ConfigurationScope.WORKSPACE),
        limit("cli.process_stop.wait_timeout", processStopTimeoutSeconds * 1_000, ConfigurationUnit.MILLISECONDS, "processStopTimeoutSeconds", ConfigurationScope.WORKSPACE),
        limit("cli.seed.project_state.maximum_bytes", maximumProjectStateBytes, ConfigurationUnit.BYTES, "maximumProjectStateBytes", ConfigurationScope.WORKSPACE),
        limit("cli.bootstrap.state.maximum_bytes", maximumBootstrapDocumentBytes.toLong(), ConfigurationUnit.BYTES, "maximumBootstrapDocumentBytes", ConfigurationScope.WORKSPACE),
        limit("cli.startup.progress.maximum_lines", maximumProgressLines.toLong(), ConfigurationUnit.COUNT, "maximumProgressLines", ConfigurationScope.WORKSPACE),
        limit("cli.startup.progress.heartbeat", progressHeartbeatMillis, ConfigurationUnit.MILLISECONDS, "progressHeartbeatMillis", ConfigurationScope.WORKSPACE),
        limit("cli.endpoint.maximum_path_bytes", maximumEndpointPathBytes.toLong(), ConfigurationUnit.BYTES, "maximumEndpointPathBytes", ConfigurationScope.WORKSPACE),
    )

    private fun limit(key: String, value: Long, unit: ConfigurationUnit, member: String, scope: ConfigurationScope) =
        ConfigurationOperationalLimit(key, ":cli", value, unit, scope, "CliOperationalLimits.$member")
}
