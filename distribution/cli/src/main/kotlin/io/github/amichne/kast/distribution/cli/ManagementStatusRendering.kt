package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostedServiceStatus

internal fun InstallationStatus.asText(): String = buildList {
    add("Command: $commandPath")
    add("Installation: ${resolvedInstallationPath.value ?: "unavailable"}")
    add("Control installed: ${installedVersion.value ?: "unavailable"}")
    add("Control running: ${loadedVersion.value ?: "unavailable"}")
    add("Installed build: ${installedBuild.value ?: "unavailable"}")
    addAll(productStatusLines(product))
    add("Update policy: ${updatePolicy.value ?: "unavailable"}")
    add("Unresolved transaction: ${unresolvedTransaction.value ?: "unavailable"}")
    addAll(hostedServices.value?.takeIf { it.isNotEmpty() }?.flatMap(::hostStatusLines) ?: unavailableHostLines())
    add("Recorded integrations: ${registrations.value?.joinToString { it.connection.publicName } ?: "unavailable"}")
    add("Active workspaces: ${activeWorkspaces.value?.joinToString() ?: "unavailable"}")
    add("Live connections: ${liveConnections.value ?: "unavailable"}")
    add("One-shot requests in flight: ${oneShotRequestsInFlight.value ?: "unavailable"}")
    addAll(workspaces.value.orEmpty().flatMap(::workspaceStatusLines))
}
    .joinToString("\n")

private fun productStatusLines(product: Observation<InstalledProductReference>): List<String> =
    product.value
        ?.let {
            listOf(
                "Product release: ${it.releaseTag}; embedded build: ${it.version}",
                "Runtime archive: ${it.runtimeArchiveSha256}",
            )
        }
        .orEmpty()

private fun workspaceStatusLines(workspace: ManagedWorkspaceStatus): List<String> =
    when (workspace) {
        is ManagedWorkspaceStatus.Rejected -> listOf("Workspace record ${workspace.entry}: ${workspace.failure}")
        is ManagedWorkspaceStatus.Recorded ->
            listOf(
                "Workspace: ${workspace.workspace}",
                "Workspace runtime: ${workspace.runtimeArchiveSha256.value ?: workspace.runtimeArchiveSha256.reason}",
                "Workspace Host: ${workspace.host.value ?: workspace.host.reason}",
            )
    }

private fun hostStatusLines(host: HostedServiceStatus): List<String> =
    when (host) {
        is HostedServiceStatus.Compatible ->
            listOf("Host workspace: ${host.root}") +
                loadedHostLines(host.host, host.hostPid, host.hostedPluginVersion, "compatible")
        is HostedServiceStatus.Incompatible ->
            listOf("Host workspace: ${host.root}") +
                loadedHostLines(host.host, host.hostPid, host.hostedPluginVersion, "incompatible")
        is HostedServiceStatus.Unavailable ->
            listOf(
                "Host workspace: ${host.root}",
                "Host loaded: unavailable",
                "Compatibility: unavailable (${host.failure})",
            )
        is HostedServiceStatus.RegistryUnavailable ->
            listOf("Host registry: ${host.registryPath}", "Compatibility: unavailable (${host.failure})")
    }

private fun loadedHostLines(host: String, pid: Long, version: String, compatibility: String): List<String> =
    listOf("Host identity: $host (PID $pid)", "Host loaded: $version", "Compatibility: $compatibility")

private fun unavailableHostLines(): List<String> = listOf("Host loaded: unavailable", "Compatibility: unavailable")
