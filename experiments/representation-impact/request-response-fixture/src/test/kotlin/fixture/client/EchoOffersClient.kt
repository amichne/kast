package fixture.client

import fixture.outcome.Outcome

// Control: in a test source set, this implementation really copies destination into the response.
class EchoOffersClient : OffersClient {
    override fun readOffers(request: ClientRequest): Outcome<ClientError, ClientResponse> =
        Outcome.success(ClientResponse(links = listOf(request.destination)))
}
