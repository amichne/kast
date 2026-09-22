package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationRejection
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement

/** Pure owner admission retains the resolved snapshot and the canonical tool-subset proof. */
class AdmittedAppServerConfiguration
private constructor(
    val configuration: ResolvedKastConfiguration,
    internal val publicEndpointMode: BrokerPublicEndpointMode,
) {
    companion object {
        fun admit(
            configuration: ResolvedKastConfiguration
        ): Refinement<AdmittedAppServerConfiguration, AppServerConfigurationFailure> {
            val inputs = configuration.ownerInputs(ConfigurationOwner.APP_SERVER)
            val endpointMode =
                when (val admission = BrokerPublicEndpointMode.admit(inputs["KAST_APP_SERVER_PUBLIC_ENDPOINT"])) {
                    is Refinement.Refined -> admission.value
                    is Refinement.Rejected -> return admission
                }
            return Refinement.Refined(AdmittedAppServerConfiguration(configuration, endpointMode))
        }
    }
}

enum class AppServerConfigurationFailure {
    INVALID_PUBLIC_ENDPOINT
}

internal sealed interface BrokerConfigurationIngressRejection {
    class Source(val failure: SavedConfigurationIngressRejection) : BrokerConfigurationIngressRejection

    class Configuration(val failure: ConfigurationRejection) : BrokerConfigurationIngressRejection

    class Owner(val failure: AppServerConfigurationFailure) : BrokerConfigurationIngressRejection
}

internal object InstalledBrokerConfigurationIngress {
    fun admit(
        environment: Map<String, String>
    ): Refinement<AdmittedAppServerConfiguration, BrokerConfigurationIngressRejection> {
        val sources =
            when (val read = InstalledSavedConfigurationIngress.load(environment)) {
                is SavedConfigurationIngress.Loaded -> read.sources
                is SavedConfigurationIngress.Rejected ->
                    return Refinement.Rejected(BrokerConfigurationIngressRejection.Source(read.rejection))
            }
        val configuration =
            when (val resolution = ResolvedKastConfiguration.resolve(sources)) {
                is Refinement.Refined -> resolution.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(BrokerConfigurationIngressRejection.Configuration(resolution.failure))
            }
        return when (val admission = AdmittedAppServerConfiguration.admit(configuration)) {
            is Refinement.Refined -> admission
            is Refinement.Rejected -> Refinement.Rejected(BrokerConfigurationIngressRejection.Owner(admission.failure))
        }
    }
}
