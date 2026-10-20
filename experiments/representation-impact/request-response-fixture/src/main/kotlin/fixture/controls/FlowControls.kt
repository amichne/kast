package fixture.controls

import fixture.client.ClientRequest
import fixture.client.ClientResponse
import fixture.outcome.Outcome
import fixture.wire.Envelope
import fixture.wire.WireError
import fixture.wire.WireRequest
import fixture.wire.WireResponse

fun sourceKnownNonempty(): Outcome<WireError, Envelope<WireResponse>> =
    Outcome.success(Envelope(WireResponse(listOf("backend-first", "backend-second")), 207))

fun sourceKnownEmpty(): Outcome<WireError, Envelope<WireResponse>> =
    Outcome.success(Envelope(WireResponse(emptyList()), 204))

fun sourceKnownBackendError(): Outcome<WireError, Envelope<WireResponse>> =
    Outcome.error(WireError(code = "BACKEND_DENIED", debugMessage = "private-debug"))

fun constantMapper(input: Outcome<WireError, WireResponse>): Outcome<WireError, ClientResponse> =
    input.map { ClientResponse(links = listOf("constant-link")) }

fun destinationOnly(request: ClientRequest): WireRequest =
    WireRequest(destination = request.destination, channel = "APP")

fun twoMatchedMapperCalls(): Pair<WireRequest, WireRequest> {
    val first = destinationOnly(ClientRequest(destination = "first-destination", sessionToken = "first-token"))
    val second = destinationOnly(ClientRequest(destination = "second-destination", sessionToken = "second-token"))
    return first to second
}
