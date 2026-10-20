package fixture.variants.v7

data class FeatureRequest(val destination: String)
data class ClientRequest(val destination: String)
data class WireRequest(val destination: String)
data class WireResponse(val link: String)
data class ClientResponse(val link: String)
data class FeatureResponse(val link: String)

// The framework supplies this function value without an analyzable source producer.
class Transport(private val adapt: (WireRequest) -> WireResponse) {
    fun send(request: WireRequest): WireResponse = adapt(request)
}

class BackendClient(private val transport: Transport) {
    fun fetch(request: ClientRequest): ClientResponse {
        val wireRequest = WireRequest(request.destination)
        val wireResponse = transport.send(wireRequest)
        return ClientResponse(wireResponse.link)
    }
}

class Feature(private val client: BackendClient) {
    fun handle(request: FeatureRequest): FeatureResponse {
        val clientRequest = ClientRequest(request.destination)
        val clientResponse = client.fetch(clientRequest)
        return FeatureResponse(clientResponse.link)
    }
}
