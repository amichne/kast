package fixture.controls

data class ClientRequest(val destination: String, val sessionToken: String)
data class WireRequest(val destination: String)
fun destinationOnly(request: ClientRequest): WireRequest = WireRequest(request.destination)
fun constantOnly(request: ClientRequest): WireRequest = WireRequest("constant")
fun twoMatchedMapperCalls(): List<WireRequest> {
    val first = destinationOnly(ClientRequest(destination = "first-destination", sessionToken = "first-token"))
    val second = destinationOnly(ClientRequest(destination = "second-destination", sessionToken = "second-token"))
    val discarded = constantOnly(ClientRequest(destination = "discarded-destination", sessionToken = "discarded-token"))
    return listOf(first, second, discarded)
}
