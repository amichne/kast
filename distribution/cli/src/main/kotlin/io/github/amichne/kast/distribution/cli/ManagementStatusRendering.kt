package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostedServiceStatus

internal fun InstallationStatus.asText(): String = buildList {
    add("Command: $commandPath")
    add("Installation: ${resolvedInstallationPath.value ?: "unavailable"}")
    add("Control installed: ${installedVersion.value ?: "unavailable"}")
    add("Control running: ${loadedVersion.value ?: "unavailable"}")
    addAll(hostedServices.value?.takeIf { it.isNotEmpty() }?.flatMap(::hostStatusLines) ?: unavailableHostLines())
    add("Recorded integrations: ${registrations.value?.joinToString { it.connection.publicName } ?: "unavailable"}")
    add("Active workspaces: ${activeWorkspaces.value?.joinToString() ?: "unavailable"}")
    add("Live connections: ${liveConnections.value ?: "unavailable"}")
    add("One-shot requests in flight: ${oneShotRequestsInFlight.value ?: "unavailable"}")
}
    .joinToString("\n")

private fun hostStatusLines(host: HostedServiceStatus): List<String> =
    listOf("Host workspace: ${host.root}") +
        when (host) {
            is HostedServiceStatus.Compatible ->
                loadedHostLines(host.host, host.hostPid, host.hostedPluginVersion, "compatible")
            is HostedServiceStatus.Incompatible ->
                loadedHostLines(host.host, host.hostPid, host.hostedPluginVersion, "incompatible")
            is HostedServiceStatus.Unavailable ->
                listOf("Host loaded: unavailable", "Compatibility: unavailable (${host.failure})")
        }

private fun loadedHostLines(host: String, pid: Long, version: String, compatibility: String): List<String> =
    listOf("Host identity: $host (PID $pid)", "Host loaded: $version", "Compatibility: $compatibility")

private fun unavailableHostLines(): List<String> = listOf("Host loaded: unavailable", "Compatibility: unavailable")
