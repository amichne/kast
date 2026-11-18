package parcel.checkout

class Pricing {
    fun quote(basket: Basket, destination: DeliveryZone, delivery: DeliveryOption): QuoteResult =
        when (delivery) {
            DeliveryOption.Home -> QuoteResult.Quoted(
                cents = if (destination == DeliveryZone.CITY) 699 else 1099,
                handoff = "home",
            )
        }
}
