package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object LoggingHostedEndpointObserver : HostedEndpointObserver {
    override fun smartModeWait(observation: HostedCorrelatedSmartModeWait) {
        Logger.getInstance(HostedEndpointService::class.java)
            .info("kast_smart_mode_wait " + Json { encodeDefaults = true }.encodeToString(observation))
    }

    override fun transport(observation: HostedTransportObservation) {
        Logger.getInstance(HostedEndpointService::class.java)
            .info("kast_transport " + Json { encodeDefaults = true }.encodeToString(observation))
    }

    override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) {
        Logger.getInstance(HostedEndpointService::class.java)
            .info("kast_hosted stage=${stage.name} outcome=${outcome.name}")
    }

    override fun rejected(stage: HostedEndpointStage, failure: HostedEndpointFailure) {
        Logger.getInstance(HostedEndpointService::class.java)
            .info("kast_hosted stage=${stage.name} outcome=REJECTED failure=${failure.name}")
    }
}
