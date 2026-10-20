package fixture.variants.v1

data class FeatureRequest(val destination: String)
data class ClientRequest(val destination: String)
data class WireRequest(val destination: String)
data class WireResponse(val link: String)
data class ClientResponse(val link: String)
data class FeatureResponse(val link: String)

// External boundary: no implementation exists in any analyzable source set.
interface Transport {
    fun send(request: WireRequest): WireResponse
}

interface BackendClient {
    fun fetch(request: ClientRequest): ClientResponse
}

class BackendClientImpl(private val transport: Transport) : BackendClient {
    override fun fetch(request: ClientRequest): ClientResponse {
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
