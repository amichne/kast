package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Staged configuration keeps its admitted selection until this single filesystem write boundary. */
internal fun writeInstallationConfiguration(
    plan: VerifiedInstallationPlan,
    stagedConfiguration: Path,
    configuration: InstallationConfigurationSelection,
) {
    Files.createDirectories(stagedConfiguration.parent)
    if (
        configuration is InstallationConfigurationSelection.Prior &&
            plan.request.controlOnly == InstallationSwitch.ENABLED &&
            plan.request.publicEndpoint == InstallationPublicEndpointSelection.Unspecified
    ) {
        Files.copy(configuration.root.resolve("config/environment"), stagedConfiguration)
        return
    }
    Files.writeString(
        stagedConfiguration,
        configurationContent(plan, configuration),
        StandardOpenOption.CREATE_NEW,
    )
    Files.setPosixFilePermissions(stagedConfiguration, PosixFilePermissions.fromString("rw-------"))
}

private fun configurationContent(
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
