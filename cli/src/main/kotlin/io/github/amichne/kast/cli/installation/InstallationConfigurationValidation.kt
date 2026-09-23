package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.AdmittedAppServerConfiguration
import io.github.amichne.kast.appserver.InstalledSavedConfigurationIngress
import io.github.amichne.kast.appserver.SavedConfigurationIngress
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class InstallationConfigurationValidationOutcome {
    ADMITTED,
    SOURCE_REJECTED,
    RESOLUTION_REJECTED,
    OWNER_REJECTED,
}

@Serializable
internal data class InstallationConfigurationValidationObservation(
    val event: String = "kast_installation_configuration",
    val outcome: InstallationConfigurationValidationOutcome,
) {
    fun toJson(): String = Json { encodeDefaults = true }.encodeToString(serializer(), this)
}

/** Validate the staged file through the same source, resolution, and owner admissions as the broker. */
internal fun validateStagedConfiguration(
    path: Path,
    observe: (InstallationConfigurationValidationObservation) -> Unit = { System.err.println(it.toJson()) },
): InstallationConfigurationValidationOutcome {
    fun recorded(outcome: InstallationConfigurationValidationOutcome): InstallationConfigurationValidationOutcome {
        observe(InstallationConfigurationValidationObservation(outcome = outcome))
        return outcome
    }

    val sources =
        when (val read = InstalledSavedConfigurationIngress.read(path.toString(), emptyMap())) {
            is SavedConfigurationIngress.Loaded -> read.sources
            is SavedConfigurationIngress.Rejected ->
                return recorded(InstallationConfigurationValidationOutcome.SOURCE_REJECTED)
        }
    val resolved =
        when (val resolution = ResolvedKastConfiguration.resolve(sources)) {
            is Refinement.Refined -> resolution.value
            is Refinement.Rejected -> return recorded(InstallationConfigurationValidationOutcome.RESOLUTION_REJECTED)
        }
    return when (AdmittedAppServerConfiguration.admit(resolved)) {
        is Refinement.Refined -> recorded(InstallationConfigurationValidationOutcome.ADMITTED)
        is Refinement.Rejected -> recorded(InstallationConfigurationValidationOutcome.OWNER_REJECTED)
    }
}
