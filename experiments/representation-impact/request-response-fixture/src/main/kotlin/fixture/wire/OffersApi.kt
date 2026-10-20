package fixture.wire

import fixture.outcome.Outcome

@Target(AnnotationTarget.FUNCTION)
annotation class HttpPost(val path: String)

data class WireRequest(val destination: String, val channel: String)
data class WireResponse(val links: List<String>)
data class WireError(val code: String, val debugMessage: String)
data class Envelope<T>(val body: T, val statusCode: Int)

object Logger {
    fun info(message: String) = println(message)
    fun warn(message: String) = println(message)
}

// The HTTP framework supplies `adapt` at run time. No source implementation exists.
class Call<E, S>(private val adapt: () -> Outcome<E, Envelope<S>>) {
    fun execute(log: (Outcome<E, Envelope<S>>) -> String = { "" }): Outcome<E, Envelope<S>> {
        val result = adapt()
        Logger.info(log(result))
        return result
    }
}

// External boundary: the HTTP framework implements this interface at run time.
interface OffersApi {
    @HttpPost("ReadOffers.v1")
    fun readOffers(headers: Map<String, String>, body: WireRequest): Call<WireError, WireResponse>
}

fun <T : Any> createApi(type: Class<T>): T =
    error("An HTTP framework supplies ${type.name} at run time.")
