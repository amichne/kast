package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.InstalledSavedConfigurationIngress
import io.github.amichne.kast.appserver.SavedConfigurationIngress
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/** The existing-IDE route admits saved and environment settings before opening a socket. */
internal fun configuredExistingIdeClient(home: Path, environment: Map<String, String>): ExistingIdeClient {
    val rejected = ExistingIdeClient { _, _ -> ExistingIdeExchange.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED) }
    val sources =
        when (val loaded = InstalledSavedConfigurationIngress.load(environment)) {
            is SavedConfigurationIngress.Loaded -> loaded.sources
            is SavedConfigurationIngress.Rejected -> return rejected
        }
    return when (val admitted = ResolvedKastConfiguration.resolve(sources)) {
        is Refinement.Refined -> ExistingIdeSocketClient(home, admitted.value.readLimits)
        is Refinement.Rejected -> rejected
    }
}
