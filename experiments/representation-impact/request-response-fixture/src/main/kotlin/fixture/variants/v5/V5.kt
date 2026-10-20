package fixture.variants.v5

import fixture.outcome.Outcome

data class FeatureRequest(val destination: String)
data class ClientRequest(val destination: String)
data class WireRequest(val destination: String)
data class WireResponse(val link: String)
data class WireError(val code: String)
data class ClientResponse(val link: String)
data class FeatureResponse(val link: String)

// External boundary: no implementation exists in any analyzable source set.
interface Transport {
    fun send(request: WireRequest): Outcome<WireError, WireResponse>
}

class BackendClient(private val transport: Transport) {
    fun fetch(request: ClientRequest): Outcome<WireError, ClientResponse> {
        val wireRequest = WireRequest(request.destination)
        val wireResponse = transport.send(wireRequest)
        return wireResponse.map { ClientResponse(it.link) }
    }
}

class Feature(private val client: BackendClient) {
    fun handle(request: FeatureRequest): Outcome<WireError, FeatureResponse> = Outcome.sequential {
        val clientRequest = ClientRequest(request.destination)
        val (clientResponse) = client.fetch(clientRequest)
        FeatureResponse(clientResponse.link)
    }
}
