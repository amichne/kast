package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object LoggingHostedEndpointObserver : HostedEndpointObserver {
    private val json = Json { encodeDefaults = true }

    override fun readIdentity(observation: HostedCorrelatedReadIdentity) {
        Logger.getInstance(HostedEndpointService::class.java)
            .info("kast_transport_read " + json.encodeToString(observation))
    }

    override fun smartModeWait(observation: HostedCorrelatedSmartModeWait) {
        Logger.getInstance(HostedEndpointService::class.java)
            .info("kast_smart_mode_wait " + json.encodeToString(observation))
    }

    override fun transport(observation: HostedTransportObservation) {
        Logger.getInstance(HostedEndpointService::class.java).info("kast_transport " + json.encodeToString(observation))
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
