package oracle

import parcel.api.CheckoutEndpoint
import parcel.api.CheckoutRequest
import parcel.checkout.Basket
import parcel.checkout.BasketLine
import parcel.checkout.DeliveryOption
import parcel.checkout.DeliveryZone
import parcel.checkout.Pricing
import parcel.checkout.QuoteFailure
import parcel.checkout.QuoteResult

fun main() {
    val city = DeliveryZone.CITY
    val regional = DeliveryZone.REGIONAL
    val pricing = Pricing()
    val endpoint = CheckoutEndpoint(pricing)
    val limits = Basket(listOf(BasketLine(3_000, 2), BasketLine(2_000, 3)))
    val overLimit = Basket(listOf(BasketLine(3_000, 2), BasketLine(2_001, 3)))
    val huge = Basket(listOf(BasketLine(Int.MAX_VALUE, 2), BasketLine(4, 1)))

    check(endpoint.quote(CheckoutRequest(limits, city)) == QuoteResult.Quoted(699, "home"))
    check(endpoint.quote(CheckoutRequest(limits, regional)) == QuoteResult.Quoted(1099, "home"))
    check(endpoint.quote(CheckoutRequest(limits, regional, "drone")) == QuoteResult.Unavailable(QuoteFailure.UNSUPPORTED_MODE))

    for ((locker, zone) in listOf("north-17" to city, "rail-4" to regional)) {
        val projected = QuoteResult.Quoted(299, "locker:$locker")
        check(endpoint.quote(CheckoutRequest(limits, zone, "locker", locker, zone)) == projected)
        check(pricing.quote(limits, zone, DeliveryOption.Locker(locker, zone)) == projected)
    }
    check(endpoint.quote(CheckoutRequest(limits, city, "locker")) == QuoteResult.Unavailable(QuoteFailure.MISSING_LOCKER))
    check(endpoint.quote(CheckoutRequest(limits, city, "locker", "  ", city)) == QuoteResult.Unavailable(QuoteFailure.MISSING_LOCKER))
    check(endpoint.quote(CheckoutRequest(limits, city, "locker", "rail-4")) == QuoteResult.Unavailable(QuoteFailure.MISSING_LOCKER))
    check(endpoint.quote(CheckoutRequest(huge, city, "locker", "rail-4", regional)) == QuoteResult.Unavailable(QuoteFailure.WRONG_ZONE))
    check(endpoint.quote(CheckoutRequest(overLimit, city, "locker", "north-17", city)) == QuoteResult.Unavailable(QuoteFailure.LOCKER_WEIGHT_LIMIT))
    check(pricing.quote(huge, regional, DeliveryOption.Locker("rail-4", regional)) == QuoteResult.Unavailable(QuoteFailure.LOCKER_WEIGHT_LIMIT))
    println("PASS")
}
