package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationRejection
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement

/** Pure owner admission retains the resolved snapshot and the canonical tool-subset proof. */
class AdmittedAppServerConfiguration
private constructor(
    val configuration: ResolvedKastConfiguration,
    internal val toolingMode: AppServerToolingMode,
    internal val toolSelection: KastToolSelection,
) {
    companion object {
        fun admit(
            configuration: ResolvedKastConfiguration
        ): Refinement<AdmittedAppServerConfiguration, AppServerConfigurationFailure> {
            val admittedTools =
                configuration
                    .ownerCandidates(ConfigurationOwner.APP_SERVER)
                    .filter { it.parameter == ConfigurationParameter.APP_SERVER_TOOLS }
                    .map { candidate ->
                        when (val admitted = KastToolSelection.admit(candidate.valueAtOwnerBoundary())) {
                            is Refinement.Refined -> admitted.value
                            is Refinement.Rejected ->
                                return Refinement.Rejected(AppServerConfigurationFailure.INVALID_TOOL_SELECTION)
                        }
                    }
            val inputs = configuration.ownerInputs(ConfigurationOwner.APP_SERVER)
            val mode =
                when (val result = AppServerToolingMode.admit(inputs[APP_SERVER_ENABLE_ENVIRONMENT])) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(AppServerConfigurationFailure.INVALID_TOOLING_MODE)
                }
            val tools = admittedTools.firstOrNull() ?: KastToolSelection.defaults()
            return Refinement.Refined(AdmittedAppServerConfiguration(configuration, mode, tools))
        }
    }
}

enum class AppServerConfigurationFailure {
    INVALID_TOOLING_MODE,
    INVALID_TOOL_SELECTION,
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
