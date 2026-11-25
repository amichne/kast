package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy

/** Every line states a fact, states that the thing is not running, or says why neither could be told. */
internal fun InstallationStatus.asText(): String = buildList {
    add("Command: $commandPath")
    add("Installation: ${resolvedInstallationPath.text { it }}")
    add("Control installed: ${installedVersion.text { it }}")
    add("Coordinator: ${coordinatorText(loadedVersion)}")
    add("Installed build: ${installedBuild.text(::buildText)}")
    addAll(productStatusLines(product))
    add("Update policy: ${updatePolicy.text(::policyText)}")
    add("Unresolved transaction: ${unresolvedTransaction.text(::transactionText)}")
    addAll(hostedServices.value.orEmpty().flatMap(::hostStatusLines))
    if (hostedServices.reason == HOST_EVIDENCE_REJECTED) add("Host loaded: ${couldNotTell(hostedServices.reason)}")
    // Only a Codex broker observes these two; the coordinator line already says whether one could be asked.
    activeWorkspaces.value?.let {
        add("Active workspaces: ${it.joinToString().ifEmpty { "none" }}")
        add("Live connections: ${liveConnections.text(Int::toString)}")
    }
    add("One-shot requests in flight: ${oneShotRequestsInFlight.text(Int::toString)}")
    addAll(workspacesText(workspaces))
}
    .joinToString("\n")

private fun <T> Observation<T>.text(render: (T) -> String): String = value?.let(render) ?: couldNotTell(reason)

private fun couldNotTell(reason: String?): String = "could not tell (${words(reason.orEmpty())})"

private fun words(code: String): String = code.lowercase().replace('_', ' ')

private fun coordinatorText(loadedVersion: Observation<String>): String =
    loadedVersion.value?.let { "running, version $it" }
        ?: when (loadedVersion.reason) {
            RuntimeObservationFailure.COORDINATOR_NOT_RUNNING.name -> "not running; the next query starts it"
            RuntimeObservationFailure.COORDINATOR_STARTING.name -> "starting"
            RuntimeObservationFailure.COORDINATOR_STARTUP_REJECTED.name -> "not running; its last startup failed"
            RuntimeObservationFailure.EXCHANGE_UNAVAILABLE.name -> "could not tell (no answer on its socket)"
            LOADED_VERSION_UNAVAILABLE -> "running, version unknown"
            else -> couldNotTell(loadedVersion.reason)
        }

private fun buildText(build: InstalledBuildIdentity): String {
    val main = build.mainRevision?.let { if (it == build.buildRevision) " on main" else "; main $it" }.orEmpty()
    return "${build.releaseTag}, commit ${build.buildRevision}$main"
}

private fun policyText(policy: UpdatePolicy): String =
    when (policy) {
        UpdatePolicy.TrackStable -> "follows the stable channel"
        UpdatePolicy.TrackMain -> "follows the preview channel"
        is UpdatePolicy.PinnedBuild -> "pinned to ${policy.identity.releaseTag}"
    }

private fun transactionText(pending: PendingInstallationRecords): String =
    pending.records.joinToString { words(it.name) }.ifEmpty { "none" }

private fun productStatusLines(product: Observation<InstalledProductReference>): List<String> =
    product.value
        ?.let {
            listOf(
                "Product release: ${it.releaseTag}; embedded build: ${it.version}",
                "Runtime archive: ${it.runtimeArchiveSha256}",
            )
        }
        .orEmpty()

private fun workspacesText(workspaces: Observation<List<ManagedWorkspaceStatus>>): List<String> {
    val none = workspaces.reason == ContainerFailure.NOT_FOUND.name || workspaces.value?.isEmpty() == true
    return if (none) listOf("Workspace: none prepared in this scope")
    else workspaces.value?.flatMap(::workspaceStatusLines) ?: listOf("Workspace: ${couldNotTell(workspaces.reason)}")
}

private fun workspaceStatusLines(workspace: ManagedWorkspaceStatus): List<String> =
    when (workspace) {
        is ManagedWorkspaceStatus.Rejected -> listOf("Workspace record ${workspace.entry}: ${workspace.failure}")
        is ManagedWorkspaceStatus.Recorded ->
            listOfNotNull(
                "Workspace: ${workspace.workspace}",
                "Workspace runtime: ${workspace.runtimeArchiveSha256.text { it }}",
                "Workspace Host: ${workspaceHostText(workspace.host)}",
                workspaceProgressLine(workspace.progress),
                "Workspace dependencies: ${workspace.dependencySharing}; Gradle home: ${workspace.gradleHome}",
                "Saved dependency configuration: ${workspace.savedDependencyConfiguration.text { words(it.name) }}",
                "Workspace seed: ${workspace.seed.text { seedText(it.result) }}",
            )
    }

private fun workspaceHostText(host: Observation<LoadedApplicationHost>): String =
    host.value?.runtimeIdentity?.let { "running, version ${it.buildIdentity.version} (PID ${it.pid})" }
        ?: when (host.reason) {
            ApplicationHostFailure.DESCRIPTOR_ABSENT.name -> "not running"
            ApplicationHostFailure.PROCESS_UNAVAILABLE.name -> "no live process found for its endpoint"
            else -> couldNotTell(host.reason)
        }

private fun seedText(result: WorkspaceSeedResult): String =
    when (result) {
        WorkspaceSeedResult.NotRequested -> "not requested"
        is WorkspaceSeedResult.Seeded -> "from workspace ${result.donor}"
        WorkspaceSeedResult.NoEligibleDonor -> "no eligible donor"
        WorkspaceSeedResult.DonorsBusy -> "donors busy"
        WorkspaceSeedResult.UnsupportedFilesystem -> "unsupported filesystem"
        is WorkspaceSeedResult.CloneFailed -> "clone failed (${words(result.stage.name)})"
        is WorkspaceSeedResult.SelectionRejected -> "selection rejected (${words(result.failure.name)})"
        is WorkspaceSeedResult.CleanupRejected -> "cleanup rejected (${words(result.failure.name)})"
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
            listOf("Host workspace: ${host.root}", "Host loaded: ${couldNotTell(host.failure.name)}")
        is HostedServiceStatus.RegistryUnavailable ->
            listOf("Host registry: ${host.registryPath}", "Host loaded: ${couldNotTell(host.failure.name)}")
    }

private fun loadedHostLines(host: String, pid: Long, version: String, compatibility: String): List<String> =
    listOf("Host identity: $host (PID $pid)", "Host loaded: $version", "Compatibility: $compatibility")
