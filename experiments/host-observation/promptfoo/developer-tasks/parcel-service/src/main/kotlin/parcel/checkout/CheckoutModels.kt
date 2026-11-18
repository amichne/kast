package parcel.checkout

enum class DeliveryZone { CITY, REGIONAL }

data class BasketLine(val weightGrams: Int, val quantity: Int) {
    init {
        require(weightGrams > 0 && quantity > 0)
    }
}

data class Basket(val lines: List<BasketLine>) {
    val totalWeightGrams: Long
        get() = lines.sumOf { it.weightGrams.toLong() * it.quantity }
}

sealed interface DeliveryOption {
    data object Home : DeliveryOption
}

enum class QuoteFailure { UNSUPPORTED_MODE, MISSING_LOCKER, WRONG_ZONE, LOCKER_WEIGHT_LIMIT }

sealed interface QuoteResult {
    data class Quoted(val cents: Int, val handoff: String) : QuoteResult
    data class Unavailable(val reason: QuoteFailure) : QuoteResult
}
