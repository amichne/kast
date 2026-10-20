package fixture.controls.elvisresult

class ElvisResultControls {
    fun leftValue(): String? = "tracked"

    fun rightValue(): String = "fallback"

    fun nullValue(): Nothing? = null

    fun never(): Nothing = throw IllegalStateException("abrupt")

    fun nullableUnit(): Unit? = Unit

    fun wrapper(value: String?): String? = value

    fun touch() = Unit

    fun normal(): String = leftValue() ?: rightValue()

    fun leftNull(): String = nullValue() ?: rightValue()

    fun rightNull(): String? = leftValue() ?: nullValue()

    fun rightBottom(): String = leftValue() ?: never()

    fun unitResult() {
        nullableUnit() ?: Unit
    }

    fun discarded(): String {
        leftValue() ?: rightValue()
        return "after"
    }

    fun guarded(): String =
        try {
            leftValue() ?: rightValue()
        } finally {
            touch()
        }

    fun foreignOperand(): String = wrapper(leftValue()) ?: rightValue()
}
