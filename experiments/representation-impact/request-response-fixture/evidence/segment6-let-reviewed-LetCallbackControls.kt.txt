package fixture.controls

class LetCallbackControls {
    fun identity(value: String): String = value.let { it }

    fun constant(value: String): String = value.let { "constant" }

    fun twoCalls(first: String, second: String): String {
        first.let { it }
        return second.let { it }
    }
}
