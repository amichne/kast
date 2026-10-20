package fixture.feature

import fixture.client.ClientRequest
import fixture.client.OffersClient
import fixture.client.OffersModule
import fixture.outcome.Outcome
import fixture.outcome.requireNotNull

data class FeatureRequest(val destination: String, val sessionToken: String)
data class FeatureResponse(val link: String)
data class FeatureError(val reason: String)

fun interface OffersProcessor {
    fun getOffer(request: FeatureRequest): Outcome<FeatureError, FeatureResponse>
}

class OffersProcessorImpl(private val client: OffersClient) : OffersProcessor {
    override fun getOffer(request: FeatureRequest): Outcome<FeatureError, FeatureResponse> = Outcome.sequential {
        val clientRequest = ClientRequest(destination = request.destination, sessionToken = request.sessionToken)
        val (response) = client.readOffers(request = clientRequest)
            .mapError { FeatureError(reason = it.code) }
        val (link) = response.links.firstOrNull().requireNotNull { FeatureError(reason = "NO_OFFER") }
        FeatureResponse(link = link)
    }
}

// Stands in for framework wiring, such as a bean factory method.
object FeatureWiring {
    fun processor(): OffersProcessor = OffersProcessorImpl(client = OffersModule.client)
}
