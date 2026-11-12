package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Path

/** Integration removal never retires the Host or removes the selected product. */
internal fun uninstallHarness(
    root: Path,
    home: Path,
    harness: Harness,
    environment: Map<String, String>,
    execute: (List<String>) -> ProcessObservation = {
        runGuidanceProvider(it, environment + ("HOME" to home.toString()))
    },
    executeLegacyCodexAt: (Path, List<String>) -> ProcessObservation = { selected, arguments ->
        runGuidanceProvider(
            arguments,
            environment + mapOf("HOME" to home.toString(), "CODEX_HOME" to selected.toString()),
        )
    },
): PluginObservation<Unit> =
    try {
        withRegistrationLock(root) {
            val removal =
                when (harness) {
                    Harness.PI -> removeRecordedGuidance(root, harness)
                    Harness.CODEX,
                    Harness.COPILOT -> removeProviderGuidance(root, home, harness, environment, execute)
                }
            when (removal) {
                is PluginObservation.Rejected -> removal
                is PluginObservation.Observed -> {
                    for (connection in HarnessConnection.entries.filter { it.harness == harness }) {
                        disconnectAdmittedConnection(root, home, connection, executeLegacyCodexAt)
                    }
                    forgetProviderRegistration(root, home, harness, environment)
                    removal
                }
            }
        }
    } catch (_: IOException) {
        PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
    } catch (_: ManagementRejected) {
        PluginObservation.Rejected(PluginInstallFailure.LegacyRegistrationRejected)
    }

private fun removeProviderGuidance(
    root: Path,
    home: Path,
    harness: Harness,
    environment: Map<String, String>,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<Unit> =
    when (val admission = AdmittedPluginBundle.admit(root, HarnessComposition.CliOnly)) {
        is PluginObservation.Rejected -> admission
        is PluginObservation.Observed -> {
            val bundle = admission.value
            when (harness) {
                Harness.CODEX ->
                    when (
                        val selected =
                            ConnectionDirectory.admit(environment["CODEX_HOME"] ?: home.resolve(".codex").toString())
                    ) {
                        is ConnectionDirectoryAdmission.Rejected ->
                            PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
                        is ConnectionDirectoryAdmission.Admitted ->
                            removeProviderResources(
                                { observePluginMarketplace(bundle, execute) },
                                { observeInstalledPlugin(bundle, selected.directory.path, execute) },
                                listOf("codex", "plugin", "remove", KAST_PLUGIN_ID, "--json"),
                                listOf("codex", "plugin", "marketplace", "remove", KAST_MARKETPLACE_NAME, "--json"),
                                execute,
                            )
                    }
                Harness.COPILOT ->
                    removeProviderResources(
                        { observeCopilotMarketplace(bundle, execute) },
                        { observeCopilotPlugin(bundle, execute) },
                        listOf("copilot", "plugin", "uninstall", KAST_PLUGIN_ID),
                        listOf("copilot", "plugin", "marketplace", "remove", KAST_MARKETPLACE_NAME),
                        execute,
                    )
                Harness.PI -> error("Pi guidance has no provider marketplace")
            }
        }
    }

private fun removeProviderResources(
    marketplace: () -> PluginObservation<MarketplacePresence>,
    plugin: () -> PluginObservation<PluginPresence>,
    removePlugin: List<String>,
    removeMarketplace: List<String>,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<Unit> {
    val market =
        when (val observed = marketplace()) {
            is PluginObservation.Rejected -> return observed
            is PluginObservation.Observed -> observed.value
        }
    val installed =
        when (val observed = plugin()) {
            is PluginObservation.Rejected -> return observed
            is PluginObservation.Observed -> observed.value
        }
    return when (
        val removed = removeProviderResource(installed, PluginPresence.ABSENT, removePlugin, plugin, execute)
    ) {
        is PluginObservation.Rejected -> removed
        is PluginObservation.Observed ->
            removeProviderResource(market, MarketplacePresence.ABSENT, removeMarketplace, marketplace, execute)
    }
}

private fun <T : Enum<T>> removeProviderResource(
    presence: T,
    absent: T,
    command: List<String>,
    observe: () -> PluginObservation<T>,
    execute: (List<String>) -> ProcessObservation,
): PluginObservation<Unit> {
    if (presence != absent) {
        when (val removed = pluginProcessResult(execute(command))) {
            is PluginObservation.Rejected -> return removed
            is PluginObservation.Observed -> Unit
        }
    }
    return when (val remaining = observe()) {
        is PluginObservation.Rejected -> remaining
        is PluginObservation.Observed ->
            if (remaining.value == absent) PluginObservation.Observed(Unit)
            else PluginObservation.Rejected(PluginInstallFailure.VerificationRejected)
    }
}
