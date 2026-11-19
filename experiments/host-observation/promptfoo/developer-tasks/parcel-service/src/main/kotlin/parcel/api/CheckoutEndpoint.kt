package parcel.api

import parcel.checkout.Basket
import parcel.checkout.DeliveryOption
import parcel.checkout.DeliveryZone
import parcel.checkout.Pricing
import parcel.checkout.QuoteFailure
import parcel.checkout.QuoteResult

data class CheckoutRequest(
    val basket: Basket,
    val destination: DeliveryZone,
    val mode: String = "home",
)

class CheckoutEndpoint(private val pricing: Pricing) {
    fun quote(request: CheckoutRequest): QuoteResult =
        when (request.mode) {
            "home" -> pricing.quote(request.basket, request.destination, DeliveryOption.Home)
            else -> QuoteResult.Unavailable(QuoteFailure.UNSUPPORTED_MODE)
        }
}
