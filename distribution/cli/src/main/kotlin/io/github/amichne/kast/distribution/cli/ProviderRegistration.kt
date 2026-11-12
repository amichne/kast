package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
internal enum class ProviderHarness(val harness: Harness) {
    CODEX(Harness.CODEX),
    COPILOT(Harness.COPILOT),
}

/** Routing intent only; removal still requires the provider's current resource ownership evidence. */
@Serializable internal data class ProviderRegistration(val harness: ProviderHarness, val directory: String)

private const val MAXIMUM_PROVIDER_REGISTRATIONS = 32

internal fun validProviders(providers: List<ProviderRegistration>): Boolean =
    providers.size <= MAXIMUM_PROVIDER_REGISTRATIONS &&
        providers.distinct().size == providers.size &&
        providers.all {
            ConnectionDirectory.admit(it.directory) is ConnectionDirectoryAdmission.Admitted
        }

/** Called under the registration lock, before the first provider mutation. */
internal fun retainProviderRegistration(root: Path, harness: ProviderHarness, directory: Path) {
    val receipt = admittedReceipt(root)
    val selected = ProviderRegistration(harness, directory.toString())
    if (selected in receipt.providers) return
    val providers = receipt.providers + selected
    if (!validProviders(providers)) throw ManagementRejected("provider-registration", "provider routing rejected")
    writeManagementReceipt(root, receipt.copy(providers = providers))
}

internal fun forgetProviderRegistration(root: Path, home: Path, harness: Harness, environment: Map<String, String>) {
    val selected =
        when (harness) {
            Harness.PI -> return
            Harness.CODEX ->
                ProviderRegistration(
                    ProviderHarness.CODEX,
                    environment["CODEX_HOME"] ?: home.resolve(".codex").toString(),
                )
            Harness.COPILOT -> ProviderRegistration(ProviderHarness.COPILOT, home.toString())
        }
    val receipt = admittedReceipt(root)
    if (selected in receipt.providers)
        writeManagementReceipt(root, receipt.copy(providers = receipt.providers - selected))
}

/** The registration lock must stay held through payload removal. */
internal fun requireProvidersRemoved(root: Path) {
    if (admittedReceipt(root).providers.isNotEmpty())
        throw ManagementRejected("uninstall-provider", "provider registration changed; retained payload")
}

internal fun removeInstallationProviders(
    root: Path,
    home: Path,
    environment: Map<String, String>,
    execute: (Map<String, String>, List<String>) -> ProcessObservation = { selected, arguments ->
        runGuidanceProvider(arguments, selected)
    },
) {
    for (provider in admittedReceipt(root).providers) {
        val selected =
            when (provider.harness) {
                ProviderHarness.CODEX ->
                    environment + mapOf("HOME" to home.toString(), "CODEX_HOME" to provider.directory)
                ProviderHarness.COPILOT -> environment + ("HOME" to provider.directory)
            }
        when (
            val removed =
                uninstallHarness(
                    root,
                    Path.of(selected.getValue("HOME")),
                    provider.harness.harness,
                    selected,
                    execute = { execute(selected, it) },
                    executeLegacyCodexAt = { directory, arguments ->
                        execute(selected + ("CODEX_HOME" to directory.toString()), arguments)
                    },
                )
        ) {
            is PluginObservation.Observed -> Unit
            is PluginObservation.Rejected ->
                throw ManagementRejected("uninstall-provider", pluginFailure(removed.failure).name)
        }
    }
}
