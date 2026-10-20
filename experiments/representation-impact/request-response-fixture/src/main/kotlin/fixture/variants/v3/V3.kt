package fixture.variants.v3

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

class BackendClient(private val transport: Transport) {
    fun fetch(request: ClientRequest): ClientResponse {
        return ClientResponse(transport.send(WireRequest(request.destination)).link)
    }
}

class Feature(private val client: BackendClient) {
    fun handle(request: FeatureRequest): FeatureResponse {
        return FeatureResponse(client.fetch(ClientRequest(request.destination)).link)
    }
}
