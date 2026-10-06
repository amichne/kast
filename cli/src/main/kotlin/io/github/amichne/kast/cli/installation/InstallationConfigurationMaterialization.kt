package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue

/** Pure literal materialization retains the admitted settings and selected installation paths. */
internal fun installationConfigurationContent(
    plan: VerifiedInstallationPlan,
    configuration: InstallationConfigurationSelection,
): String {
    val values =
        configurationValues(configuration) +
            mapOf(
                "KAST_INSTALL_IDEA_HOME" to plan.request.ideaHome.value.toString(),
                "KAST_RUNTIME_DIRECTORY" to plan.targetRoot.resolve("state/run").toString(),
                "KAST_APP_SERVER_PUBLIC_ENDPOINT" to configuration.endpoint.configurationValue,
            )
    return buildString {
        appendLine("# Kast runtime configuration. Values are literal; shell syntax is not evaluated.")
        values.toSortedMap().forEach { (key, value) -> appendLine("$key=$value") }
    }
}

private fun configurationValues(configuration: InstallationConfigurationSelection): Map<String, String> {
    val declarations =
        KastConfigurationCatalogue.declarations.filter {
            ConfigurationSource.SAVED_INSTALLATION in it.sources
        }
    return if (configuration is InstallationConfigurationSelection.Prior) {
        val keys = declarations.map { it.key }.toSet()
        configuration.admission.configuration.launchEnvironment().variables.filterKeys { it in keys }
    } else {
        declarations.filter { it.defaultValue != null }.associate { it.key to checkNotNull(it.defaultValue) }
    }
}
