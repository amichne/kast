package fixture.variants.v8

data class FeatureRequest(val destination: String)
data class ClientRequest(val destination: String)
data class WireRequest(val channel: String = "APP", val destination: String, val locale: String = "en-US")
data class WireResponse(val link: String)
data class ClientResponse(val link: String)
data class FeatureResponse(val link: String)

// External boundary: no implementation exists in any analyzable source set.
interface Transport {
    fun send(request: WireRequest): WireResponse
}

class BackendClient(private val transport: Transport) {
    fun fetch(request: ClientRequest): ClientResponse {
        val wireRequest = WireRequest(destination = request.destination, locale = "en-GB")
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
