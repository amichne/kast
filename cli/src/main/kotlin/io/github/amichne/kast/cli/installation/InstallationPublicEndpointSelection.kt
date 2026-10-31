package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.AdmittedAppServerConfiguration
import io.github.amichne.kast.distribution.contract.configuration.BrokerPublicEndpointMode
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Installation admits only private endpoints. Saved shared endpoints belong to legacy retirement. */
internal sealed interface InstallationPublicEndpointSelection {
    data object Unspecified : InstallationPublicEndpointSelection

    data object ExplicitPrivate : InstallationPublicEndpointSelection

    companion object {
        fun admit(raw: String?): Refinement<InstallationPublicEndpointSelection, InstallationRequestFailure> =
            when (raw) {
                null -> Refinement.Refined(Unspecified)
                "private" -> Refinement.Refined(ExplicitPrivate)
                else ->
                    Refinement.Rejected(
                        InstallationRequestFailure.InvalidValue(InstallationEnvironment.PUBLIC_ENDPOINT)
                    )
            }
    }
}

/** Materialization retains admitted prior settings until the staged file is written. */
internal sealed interface InstallationConfigurationSelection {
    val endpoint: BrokerPublicEndpointMode
        get() = BrokerPublicEndpointMode.PRIVATE

    val reason: InstallationPublicEndpointSelectionReason

    data object FreshDefault : InstallationConfigurationSelection {
        override val reason = InstallationPublicEndpointSelectionReason.FRESH_DEFAULT
    }

    data object FreshExplicit : InstallationConfigurationSelection {
        override val reason = InstallationPublicEndpointSelectionReason.EXPLICIT
    }

    data class Prior(
        val root: Path,
        val admission: AdmittedAppServerConfiguration,
        private val selection: InstallationPublicEndpointSelection,
        val migration: InstallationConfigurationMigration = InstallationConfigurationMigration.Unchanged,
    ) : InstallationConfigurationSelection {
        fun preservesEndpoint(): Boolean = endpoint == admission.publicEndpointMode

        override val reason: InstallationPublicEndpointSelectionReason
            get() =
                when (selection) {
                    InstallationPublicEndpointSelection.ExplicitPrivate ->
                        InstallationPublicEndpointSelectionReason.EXPLICIT
                    InstallationPublicEndpointSelection.Unspecified ->
                        when (admission.publicEndpointMode) {
                            BrokerPublicEndpointMode.PRIVATE ->
                                InstallationPublicEndpointSelectionReason.SAVED_CONFIGURATION
                            BrokerPublicEndpointMode.CODEX_CONTROL ->
                                InstallationPublicEndpointSelectionReason.LEGACY_SHARED_ENDPOINT_MIGRATED
                        }
                }
    }
}

@Serializable
internal enum class InstallationPublicEndpointSelectionReason {
    FRESH_DEFAULT,
    SAVED_CONFIGURATION,
    EXPLICIT,
    LEGACY_SHARED_ENDPOINT_MIGRATED,
}

@Serializable
internal data class InstallationPublicEndpointObservation(
    val event: String = "kast_installation_public_endpoint",
    val endpoint: BrokerPublicEndpointMode,
    val reason: InstallationPublicEndpointSelectionReason,
) {
    fun toJson(): String = Json { encodeDefaults = true }.encodeToString(serializer(), this)
}

/** This effect boundary records only closed selection/failure evidence, never raw configuration or paths. */
internal fun observeInstallationConfigurationSelection(
    plan: VerifiedInstallationPlan,
    prior: Path?,
): Refinement<InstallationConfigurationSelection, InstallationConfigurationFailure> {
    val retainedSettings =
        when (plan.request.force) {
            InstallationSwitch.DISABLED -> prior
            InstallationSwitch.ENABLED -> null
        }
    val selected = selectInstallationConfiguration(plan.request.publicEndpoint, retainedSettings)
    when (selected) {
        is Refinement.Refined -> {
            val configuration = selected.value
            if (configuration is InstallationConfigurationSelection.Prior) {
                val migration = configuration.migration
                if (migration is InstallationConfigurationMigration.StableModuleCount)
                    System.err.println(InstallationConfigurationMigrationObservation(migration.count.value).toJson())
            }
            System.err.println(
                InstallationPublicEndpointObservation(
                        endpoint = selected.value.endpoint,
                        reason = selected.value.reason,
                    )
                    .toJson()
            )
        }
        is Refinement.Rejected ->
            System.err.println(
                InstallationConfigurationValidationObservation(outcome = selected.failure.observation).toJson()
            )
    }
    return selected
}

internal fun selectInstallationConfiguration(
    selection: InstallationPublicEndpointSelection,
    prior: Path?,
): Refinement<InstallationConfigurationSelection, InstallationConfigurationFailure> {
    if (prior == null) {
        return when (selection) {
            InstallationPublicEndpointSelection.ExplicitPrivate ->
                Refinement.Refined(InstallationConfigurationSelection.FreshExplicit)
            InstallationPublicEndpointSelection.Unspecified ->
                Refinement.Refined(InstallationConfigurationSelection.FreshDefault)
        }
    }
    val configuration =
        when (val admitted = readPriorInstallationConfiguration(prior)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return Refinement.Refined(
        InstallationConfigurationSelection.Prior(prior, configuration.admission, selection, configuration.migration)
    )
}
