package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Path

internal const val GUIDANCE_REFRESH_CAPABILITY = "share/kast/guidance-refresh-v1"

internal fun refreshCompletedInstallation(
    root: Path,
    environment: Map<String, String>,
    enrollCodex: Boolean,
): LifecycleEffect =
    try {
        val installation = selectedInstallation(root)
        val manifest = readBundledManifest(installation)
        if (
            manifest is BundledManifestRead.Read &&
                manifest.manifest.payloadFiles.none { it.path == GUIDANCE_REFRESH_CAPABILITY }
        )
            LifecycleEffect.Completed
        else if (
            manifest !is BundledManifestRead.Read ||
                !payloadOwned(installation, manifest.manifest, GUIDANCE_REFRESH_CAPABILITY)
        )
            LifecycleEffect.Rejected(LifecycleFailure.OWNERSHIP_UNPROVEN)
        else
            when (val home = ConnectionDirectory.admit(environment["HOME"].orEmpty())) {
                is ConnectionDirectoryAdmission.Admitted ->
                    refreshInstalledGuidance(
                        root,
                        home.directory.path,
                        environment,
                        enrollCodex,
                    )
                is ConnectionDirectoryAdmission.Rejected ->
                    LifecycleEffect.Rejected(LifecycleFailure.OWNERSHIP_UNPROVEN)
            }
    } catch (_: ManagementRejected) {
        LifecycleEffect.Rejected(LifecycleFailure.REGISTRATION_REPAIR_REQUIRED)
    } catch (_: IOException) {
        LifecycleEffect.Rejected(LifecycleFailure.REGISTRATION_REPAIR_REQUIRED)
    } catch (_: SecurityException) {
        LifecycleEffect.Rejected(LifecycleFailure.REGISTRATION_REPAIR_REQUIRED)
    }

/** Provider inventories own existing plugin selections. The management receipt owns Pi's copied skill. */
internal fun refreshInstalledGuidance(
    root: Path,
    home: Path,
    environment: Map<String, String>,
    enrollCodex: Boolean = false,
): LifecycleEffect {
    val receipt = requireOwnedExecutable(root)
    val execution = GuidanceProviderExecution.native(environment + ("HOME" to home.toString()))
    val codex = refreshCodexGuidance(root, home, environment, execution, enrollCodex)
    val copilot =
        refreshProvider(
            root,
            Harness.COPILOT,
            { observeCopilotMarketplace(it, execution.command) },
            { observeCopilotPlugin(it, execution.command) },
        ) {
            installCopilotGuidance(root, home, it, environment, execution)
        }
    val pi = refreshRecordedPiGuidance(root, home, receipt, environment)
    return if (listOf(codex, copilot, pi).all { it == LifecycleEffect.Completed }) LifecycleEffect.Completed
    else LifecycleEffect.Rejected(LifecycleFailure.REGISTRATION_REPAIR_REQUIRED)
}

private fun refreshCodexGuidance(
    root: Path,
    home: Path,
    environment: Map<String, String>,
    execution: GuidanceProviderExecution,
    enroll: Boolean,
): LifecycleEffect {
    val rawHome = environment["CODEX_HOME"] ?: home.resolve(".codex").toString()
    val directory =
        when (val selected = ConnectionDirectory.admit(rawHome)) {
            is ConnectionDirectoryAdmission.Admitted -> selected.directory
            is ConnectionDirectoryAdmission.Rejected ->
                return guidanceRefreshRejected(Harness.CODEX, ProvisionFailure.DESTINATION_UNAVAILABLE)
        }
    return refreshProvider(
        root,
        Harness.CODEX,
        { observePluginMarketplace(it, execution.command) },
        { observeInstalledPlugin(it, directory.path, execution.command) },
        enroll,
    ) {
        codexGuidance(root, home, it, environment)
    }
}

private fun refreshProvider(
    root: Path,
    harness: Harness,
    marketplace: (AdmittedPluginBundle) -> PluginObservation<MarketplacePresence>,
    plugin: (AdmittedPluginBundle) -> PluginObservation<PluginPresence>,
    enroll: Boolean = false,
    provision: (HarnessComposition) -> ProvisionOutcome,
): LifecycleEffect {
    val cli =
        when (val result = AdmittedPluginBundle.admit(root)) {
            is PluginObservation.Observed -> result.value
            is PluginObservation.Rejected -> return guidanceRefreshRejected(harness, pluginFailure(result.failure))
        }
    val selected =
        when (val observed = marketplace(cli)) {
            is PluginObservation.Observed -> observed.value
            is PluginObservation.Rejected ->
                return when (observed.failure) {
                    PluginInstallFailure.ProcessUnavailable,
                    PluginInstallFailure.MarketplaceConflict -> enrollGuidance(harness, enroll, provision)
                    else -> guidanceRefreshRejected(harness, pluginFailure(observed.failure))
                }
        }
    return when (selected) {
        MarketplacePresence.ABSENT -> enrollGuidance(harness, enroll, provision)
        MarketplacePresence.ADMITTED -> refreshSelectedProvider(cli, harness, enroll, plugin, provision)
        MarketplacePresence.OWNED_OTHER ->
            when (val mcp = AdmittedPluginBundle.admit(root, HarnessComposition.CliWithMcp)) {
                is PluginObservation.Rejected -> guidanceRefreshRejected(harness, pluginFailure(mcp.failure))
                is PluginObservation.Observed -> refreshSelectedProvider(mcp.value, harness, enroll, plugin, provision)
            }
    }
}

private fun enrollGuidance(
    harness: Harness,
    enroll: Boolean,
    provision: (HarnessComposition) -> ProvisionOutcome,
): LifecycleEffect =
    if (enroll) finishGuidanceRefresh(harness, provision(HarnessComposition.CliOnly)) else LifecycleEffect.Completed

private fun refreshSelectedProvider(
    bundle: AdmittedPluginBundle,
    harness: Harness,
    enroll: Boolean,
    plugin: (AdmittedPluginBundle) -> PluginObservation<PluginPresence>,
    provision: (HarnessComposition) -> ProvisionOutcome,
): LifecycleEffect =
    when (val observed = plugin(bundle)) {
        is PluginObservation.Observed ->
            when (observed.value) {
                PluginPresence.CURRENT -> LifecycleEffect.Completed
                PluginPresence.ADMITTED -> finishGuidanceRefresh(harness, provision(bundle.composition))
                PluginPresence.ABSENT ->
                    if (enroll) finishGuidanceRefresh(harness, provision(bundle.composition))
                    else LifecycleEffect.Completed
            }
        is PluginObservation.Rejected -> guidanceRefreshRejected(harness, pluginFailure(observed.failure))
    }

private fun refreshRecordedPiGuidance(
    root: Path,
    home: Path,
    receipt: ManagementReceipt,
    environment: Map<String, String>,
): LifecycleEffect {
    val skill = receipt.guidance.singleOrNull { it.harness == Harness.PI } ?: return LifecycleEffect.Completed
    val destination = Path.of(skill.destination)
    if (destination.fileName.toString() != "kast" || destination.parent?.fileName?.toString() != "skills")
        return guidanceRefreshRejected(Harness.PI, ProvisionFailure.DESTINATION_UNAVAILABLE)
    val directory = destination.parent.parent
    val registration = receipt.registrations.singleOrNull { it.connection == HarnessConnection.PI }
    val composition = registration?.composition ?: HarnessComposition.CliOnly
    return finishGuidanceRefresh(
        Harness.PI,
        installPiGuidance(root, home, composition, environment + ("PI_CODING_AGENT_DIR" to directory.toString())),
    )
}

private fun finishGuidanceRefresh(harness: Harness, outcome: ProvisionOutcome): LifecycleEffect =
    when (outcome) {
        is ProvisionOutcome.Installed,
        is ProvisionOutcome.AlreadyInstalled -> LifecycleEffect.Completed
        is ProvisionOutcome.Rejected -> guidanceRefreshRejected(harness, outcome.reason)
        is ProvisionOutcome.RecoveryRequired -> guidanceRefreshRejected(harness, outcome.reason)
    }

private fun guidanceRefreshRejected(harness: Harness, reason: ProvisionFailure): LifecycleEffect.Rejected {
    System.err.println("kast: guidance-refresh: ${harness.publicName}: $reason")
    return LifecycleEffect.Rejected(LifecycleFailure.REGISTRATION_REPAIR_REQUIRED)
}
