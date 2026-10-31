package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.AdmittedAppServerConfiguration
import io.github.amichne.kast.appserver.InstalledSavedConfigurationIngress
import io.github.amichne.kast.appserver.SavedConfigurationIngress
import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val LEGACY_MODULE_COUNT_MAXIMUM = 2_147_483_646

internal sealed interface InstallationConfigurationMigration {
    data object Unchanged : InstallationConfigurationMigration

    data class StableModuleCount(val count: LegacyModuleCount) : InstallationConfigurationMigration
}

/** The v0.50.2 ReadLimitValue grammar remains installation-only after this setting was removed. */
internal class LegacyModuleCount private constructor(val value: Int) {
    companion object {
        fun admit(raw: String): Refinement<LegacyModuleCount, InstallationConfigurationFailure> {
            if (raw.isEmpty() || raw.any { it !in '0'..'9' })
                return Refinement.Rejected(InstallationConfigurationFailure.RESOLUTION_REJECTED)
            val count = raw.toIntOrNull()
            return if (count != null && count in 1..LEGACY_MODULE_COUNT_MAXIMUM)
                Refinement.Refined(LegacyModuleCount(count))
            else Refinement.Rejected(InstallationConfigurationFailure.RESOLUTION_REJECTED)
        }
    }
}

internal data class PriorInstallationConfiguration(
    val admission: AdmittedAppServerConfiguration,
    val migration: InstallationConfigurationMigration,
)

@Serializable
internal data class InstallationConfigurationMigrationObservation(
    val retiredModuleCount: Int,
    val event: String = "kast_installation_configuration_migration",
    val sourceVersion: String = "0.50.2",
    val key: String = "KAST_READ_MODEL_MODULES",
) {
    fun toJson(): String = Json { encodeDefaults = true }.encodeToString(serializer(), this)
}

/** Saved ingress keeps ordering and duplicates. Only the exact released setting receives migration authority. */
internal fun readPriorInstallationConfiguration(
    prior: Path
): Refinement<PriorInstallationConfiguration, InstallationConfigurationFailure> {
    val sources =
        when (
            val read =
                InstalledSavedConfigurationIngress.read(prior.resolve("config/environment").toString(), emptyMap())
        ) {
            is SavedConfigurationIngress.Loaded -> read.sources
            is SavedConfigurationIngress.Rejected ->
                return Refinement.Rejected(InstallationConfigurationFailure.SOURCE_REJECTED)
        }
    val retired = sources.savedInstallation.filter { it.first == "KAST_READ_MODEL_MODULES" }
    val migration =
        if (retired.isEmpty()) InstallationConfigurationMigration.Unchanged
        else {
            if (retired.size != 1 || !isStableConfigurationOwner(prior))
                return Refinement.Rejected(InstallationConfigurationFailure.RESOLUTION_REJECTED)
            when (val admitted = LegacyModuleCount.admit(retired.single().second)) {
                is Refinement.Refined -> InstallationConfigurationMigration.StableModuleCount(admitted.value)
                is Refinement.Rejected -> return admitted
            }
        }
    val current =
        sources.copy(savedInstallation = sources.savedInstallation.filterNot { it.first == "KAST_READ_MODEL_MODULES" })
    return when (val admitted = resolveInstallationConfiguration(current)) {
        is Refinement.Refined -> Refinement.Refined(PriorInstallationConfiguration(admitted.value, migration))
        is Refinement.Rejected -> admitted
    }
}

private fun isStableConfigurationOwner(prior: Path): Boolean {
    val raw =
        readInstallationBounded(
            prior.resolve("installation.json"),
            ControlDistributionLimits.maximumManifestBytes.toLong(),
        ) ?: return false
    val manifest =
        try {
            installationManifestJson.decodeFromString(InstallationManifest.serializer(), raw)
        } catch (_: SerializationException) {
            return false
        }
    return manifest.schemaVersion == INSTALLATION_MANIFEST_SCHEMA_VERSION &&
        manifest.semanticVersion == "0.50.2" &&
        manifest.installationRoot == prior.toString() &&
        manifest.configuration == prior.resolve("config/environment").toString()
}
