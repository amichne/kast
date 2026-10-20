package fixture.client

import fixture.outcome.Outcome
import fixture.wire.Envelope
import fixture.wire.Logger
import fixture.wire.OffersApi
import fixture.wire.WireError
import fixture.wire.WireRequest
import fixture.wire.WireResponse
import fixture.wire.createApi

data class ClientRequest(val destination: String, val sessionToken: String)
data class ClientResponse(val links: List<String>)
data class ClientError(val code: String)

interface OffersClient {
    fun readOffers(request: ClientRequest): Outcome<ClientError, ClientResponse>
}

internal class OffersClientImpl(
    private val api: OffersApi,
    private val headers: HeaderBuilder,
) : OffersClient {
    override fun readOffers(request: ClientRequest): Outcome<ClientError, ClientResponse> =
        api.readOffers(
            headers = headers.build(request.sessionToken),
            body = request.toWireRequest(),
        ).execute(
            log = { it.toLogLine() },
        ).map { it.body.toClientResponse() }
            .mapError { error ->
                Logger.warn("read offers failed: ${error.debugMessage}")
                error.toClientError()
            }
}

class HeaderBuilder {
    fun build(sessionToken: String): Map<String, String> = mapOf("Authorization" to "Bearer $sessionToken")
}

internal fun ClientRequest.toWireRequest(): WireRequest = WireRequest(destination = destination, channel = "APP")

internal fun WireResponse.toClientResponse(): ClientResponse = ClientResponse(links = links)

internal fun WireError.toClientError(): ClientError = ClientError(code = code)

private fun Outcome<WireError, Envelope<WireResponse>>.toLogLine(): String = when (this) {
    is Outcome.Success -> "status=${value.statusCode}"
    is Outcome.Error -> "code=${value.code}"
}

object OffersModule {
    val client: OffersClient by lazy {
        OffersClientImpl(api = createApi(OffersApi::class.java), headers = HeaderBuilder())
    }
}
