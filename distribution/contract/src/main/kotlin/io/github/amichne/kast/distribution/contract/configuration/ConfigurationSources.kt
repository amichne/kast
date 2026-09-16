package io.github.amichne.kast.distribution.contract.configuration

/** Raw inputs exist only until admission. Lists deliberately preserve duplicate saved assignments. */
data class ConfigurationSources(
    val environment: Map<String, String> = emptyMap(),
    val savedInstallation: List<Pair<String, String>> = emptyList(),
    val savedWorkspace: List<Pair<String, String>> = emptyList(),
    val commandLine: List<Pair<String, String>> = emptyList(),
) {
    override fun toString(): String =
        "ConfigurationSources(environmentKeys=${environment.keys.size}, savedInstallationAssignments=${savedInstallation.size}, savedWorkspaceAssignments=${savedWorkspace.size}, commandLineAssignments=${commandLine.size})"
}
