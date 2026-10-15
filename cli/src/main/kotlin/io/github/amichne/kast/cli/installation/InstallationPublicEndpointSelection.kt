package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.AdmittedAppServerConfiguration
import io.github.amichne.kast.distribution.contract.configuration.BrokerPublicEndpointMode
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Absence retains the caller's decision to preserve an admitted prior endpoint. */
internal sealed interface InstallationPublicEndpointSelection {
    data object Unspecified : InstallationPublicEndpointSelection

    data class Explicit(val mode: BrokerPublicEndpointMode) : InstallationPublicEndpointSelection

    companion object {
        fun admit(raw: String?): Refinement<InstallationPublicEndpointSelection, InstallationRequestFailure> =
            when (raw) {
                null -> Refinement.Refined(Unspecified)
                else ->
                    when (val endpoint = BrokerPublicEndpointMode.admit(raw)) {
                        is Refinement.Refined -> Refinement.Refined(Explicit(endpoint.value))
                        is Refinement.Rejected ->
                            Refinement.Rejected(
                                InstallationRequestFailure.InvalidValue(InstallationEnvironment.PUBLIC_ENDPOINT)
                            )
                    }
            }
    }
}

/** Materialization retains admitted prior settings until the staged file is written. */
internal sealed interface InstallationConfigurationSelection {
    val endpoint: BrokerPublicEndpointMode
    val reason: InstallationPublicEndpointSelectionReason

    data object FreshDefault : InstallationConfigurationSelection {
        override val endpoint = BrokerPublicEndpointMode.PRIVATE
        override val reason = InstallationPublicEndpointSelectionReason.FRESH_DEFAULT
    }

    data class FreshExplicit(override val endpoint: BrokerPublicEndpointMode) : InstallationConfigurationSelection {
        override val reason = InstallationPublicEndpointSelectionReason.EXPLICIT
    }

    data class Prior(
        val root: Path,
        val admission: AdmittedAppServerConfiguration,
        private val selection: InstallationPublicEndpointSelection,
    ) : InstallationConfigurationSelection {
        override val endpoint: BrokerPublicEndpointMode
            get() =
                when (selection) {
                    is InstallationPublicEndpointSelection.Explicit -> selection.mode
                    InstallationPublicEndpointSelection.Unspecified -> admission.publicEndpointMode
                }

        fun preservesEndpoint(): Boolean = endpoint == admission.publicEndpointMode

        override val reason: InstallationPublicEndpointSelectionReason
            get() =
                when (selection) {
                    is InstallationPublicEndpointSelection.Explicit ->
                        InstallationPublicEndpointSelectionReason.EXPLICIT
                    InstallationPublicEndpointSelection.Unspecified ->
                        InstallationPublicEndpointSelectionReason.SAVED_CONFIGURATION
                }
    }
}

@Serializable
internal enum class InstallationPublicEndpointSelectionReason {
    FRESH_DEFAULT,
    SAVED_CONFIGURATION,
    EXPLICIT,
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
        is Refinement.Refined ->
            System.err.println(
                InstallationPublicEndpointObservation(
                        endpoint = selected.value.endpoint,
                        reason = selected.value.reason,
                    )
                    .toJson()
            )
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
            is InstallationPublicEndpointSelection.Explicit ->
                Refinement.Refined(InstallationConfigurationSelection.FreshExplicit(selection.mode))
            InstallationPublicEndpointSelection.Unspecified ->
                Refinement.Refined(InstallationConfigurationSelection.FreshDefault)
        }
    }
    val configuration =
        when (val admitted = readInstallationConfiguration(prior.resolve("config/environment"))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return Refinement.Refined(InstallationConfigurationSelection.Prior(prior, configuration, selection))
}
