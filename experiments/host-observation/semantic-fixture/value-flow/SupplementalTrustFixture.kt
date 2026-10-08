package representation.supplemental

fun unitProducer(): Unit = Unit

fun bottomProducer(): Nothing = throw IllegalStateException("bottom")

fun nullableBottomProducer(): Nothing? = null

fun tryFinalUnit(): Unit = try {
    unitProducer()
} catch (failure: IllegalStateException) {
    Unit
}

fun tryFinalNothing(): Nothing = try {
    bottomProducer()
} catch (failure: IllegalStateException) {
    throw failure
}

fun tryFinalNullableBottom(): Nothing? = try {
    nullableBottomProducer()
} catch (failure: IllegalStateException) {
    null
}

enum class EntryLocalOwner {
    FIRST {
        override fun compute(input: String): String {
            val binding = input
            fun named(value: String): String = binding + value
            return named(binding)
        }
    },
    SECOND {
        override fun compute(input: String): String {
            val binding = input
            fun named(value: String): String = value + binding
            return named(binding)
        }
    };

    abstract fun compute(input: String): String
}
